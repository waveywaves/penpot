;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.loggers.audit
  "Services related to the user activity (audit log)."
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.exceptions :as ex]
   [app.common.logging :as l]
   [app.common.spec :as us]
   [app.common.transit :as t]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.http.client :as http]
   [app.loggers.audit.tasks :as-alias tasks]
   [app.loggers.webhooks :as-alias webhooks]
   [app.main :as-alias main]
   [app.metrics :as mtx]
   [app.rpc :as-alias rpc]
   [app.tokens :as tokens]
   [app.util.retry :as rtry]
   [app.util.time :as dt]
   [app.worker :as wrk]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [integrant.core :as ig]
   [lambdaisland.uri :as u]
   [promesa.core :as p]
   [promesa.exec :as px]
   [yetti.request :as yrq]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn parse-client-ip
  [request]
  (or (some-> (yrq/get-header request "x-forwarded-for") (str/split ",") first)
      (yrq/get-header request "x-real-ip")
      (some-> (yrq/remote-addr request) str)))

(defn extract-utm-params
  "Extracts additional data from params and namespace them under
  `penpot` ns."
  [params]
  (letfn [(process-param [params k v]
            (let [sk (d/name k)]
              (cond-> params
                (str/starts-with? sk "utm_")
                (assoc (->> sk str/kebab (keyword "penpot")) v)

                (str/starts-with? sk "mtm_")
                (assoc (->> sk str/kebab (keyword "penpot")) v))))]
    (reduce-kv process-param {} params)))

(def ^:private
  profile-props
  [:id
   :is-active
   :is-muted
   :auth-backend
   :email
   :default-team-id
   :default-project-id
   :fullname
   :lang])

(defn profile->props
  [profile]
  (-> profile
      (select-keys profile-props)
      (merge (:props profile))
      (d/without-nils)))

(defn clean-props
  [{:keys [profile-id] :as event}]
  (let [invalid-keys #{:session-id
                       :password
                       :old-password
                       :token}
        xform (comp
               (remove (fn [kv]
                         (qualified-keyword? (first kv))))
               (remove (fn [kv]
                         (contains? invalid-keys (first kv))))
               (remove (fn [[k v]]
                         (and (= k :profile-id)
                              (= v profile-id))))
               (filter (fn [[_ v]]
                         (or (string? v)
                             (keyword? v)
                             (uuid? v)
                             (boolean? v)
                             (number? v)))))]

    (update event :props #(into {} xform %))))

;; --- SPECS

(s/def ::profile-id ::us/uuid)
(s/def ::name ::us/string)
(s/def ::type ::us/string)
(s/def ::props (s/map-of ::us/keyword any?))
(s/def ::ip-addr ::us/string)

(s/def ::webhooks/event? ::us/boolean)
(s/def ::webhooks/batch-timeout ::dt/duration)
(s/def ::webhooks/batch-key
  (s/or :fn fn? :str string? :kw keyword?))

(s/def ::event
  (s/keys :req-un [::type ::name ::profile-id]
          :opt-un [::ip-addr ::props]
          :opt [::webhooks/event?
                ::webhooks/batch-timeout
                ::webhooks/batch-key]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; COLLECTOR
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Defines a service that collects the audit/activity log using
;; internal database. Later this audit log can be transferred to
;; an external storage and data cleared.

(s/def ::collector
  (s/keys :req [::wrk/executor ::db/pool]))

(defmethod ig/pre-init-spec ::collector [_]
  (s/keys :req [::db/pool ::wrk/executor ::mtx/metrics]))

(defmethod ig/init-key ::collector
  [_ {:keys [::db/pool] :as cfg}]
  (cond
    (db/read-only? pool)
    (l/warn :hint "audit: disabled (db is read-only)")

    :else
    cfg))

(defn- persist-event!
  [pool event]
  (us/verify! ::event event)
  (let [params {:id (uuid/next)
                :name (:name event)
                :type (:type event)
                :profile-id (:profile-id event)
                :ip-addr (:ip-addr event)
                :props (:props event)}]

    (when (contains? cf/flags :audit-log)
      ;; NOTE: this operation may cause primary key conflicts on inserts
      ;; because of the timestamp precission (two concurrent requests), in
      ;; this case we just retry the operation.
      (rtry/with-retry {::rtry/when rtry/conflict-exception?
                        ::rtry/max-retries 6
                        ::rtry/label "persist-audit-log-event"}
        (let [now (dt/now)]
          (db/insert! pool :audit-log
                      (-> params
                          (update :props db/tjson)
                          (update :ip-addr db/inet)
                          (assoc :created-at now)
                          (assoc :tracked-at now)
                          (assoc :source "backend"))))))

    (when (and (contains? cf/flags :webhooks)
               (::webhooks/event? event))
      (let [batch-key     (::webhooks/batch-key event)
            batch-timeout (::webhooks/batch-timeout event)
            label         (dm/str "rpc:" (:name params))
            label         (cond
                            (ifn? batch-key)    (dm/str label ":" (batch-key (::rpc/params event)))
                            (string? batch-key) (dm/str label ":" batch-key)
                            :else               label)
            dedupe?       (boolean (and batch-key batch-timeout))]

        (wrk/submit! ::wrk/conn pool
                     ::wrk/task :process-webhook-event
                     ::wrk/queue :webhooks
                     ::wrk/max-retries 0
                     ::wrk/delay (or batch-timeout 0)
                     ::wrk/dedupe dedupe?
                     ::wrk/label label

                     ::webhooks/event
                     (-> params
                         (dissoc :ip-addr)
                         (dissoc :type)))))))

(defn submit!
  "Submit audit event to the collector."
  [{:keys [::wrk/executor ::db/pool] :as collector} params]
  (us/assert! ::collector collector)
  (->> (px/submit! executor (partial persist-event! pool (d/without-nils params)))
       (p/merr (fn [cause]
                 (l/error :hint "audit: unexpected error processing event" :cause cause)
                 (p/resolved nil)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TASK: ARCHIVE
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; This is a task responsible to send the accumulated events to
;; external service for archival.

(declare archive-events)

(s/def ::tasks/uri ::us/string)

(defmethod ig/pre-init-spec ::tasks/archive-task [_]
  (s/keys :req [::db/pool ::main/props ::http/client]))

(defmethod ig/init-key ::tasks/archive
  [_ cfg]
  (fn [params]
    ;; NOTE: this let allows overwrite default configured values from
    ;; the repl, when manually invoking the task.
    (let [enabled (or (contains? cf/flags :audit-log-archive)
                      (:enabled params false))
          uri     (cf/get :audit-log-archive-uri)
          uri     (or uri (:uri params))
          cfg     (assoc cfg ::uri uri)]

      (when (and enabled (not uri))
        (ex/raise :type :internal
                  :code :task-not-configured
                  :hint "archive task not configured, missing uri"))

      (when enabled
        (loop [total 0]
          (let [n (archive-events cfg)]
            (if n
              (do
                (px/sleep 100)
                (recur (+ total n)))
              (when (pos? total)
                (l/debug :hint "events archived" :total total)))))))))

(def ^:private sql:retrieve-batch-of-audit-log
  "select *
     from audit_log
    where archived_at is null
    order by created_at asc
    limit 256
      for update skip locked;")

(defn archive-events
  [{:keys [::db/pool ::uri] :as cfg}]
  (letfn [(decode-row [{:keys [props ip-addr context] :as row}]
            (cond-> row
              (db/pgobject? props)
              (assoc :props (db/decode-transit-pgobject props))

              (db/pgobject? context)
              (assoc :context (db/decode-transit-pgobject context))

              (db/pgobject? ip-addr "inet")
              (assoc :ip-addr (db/decode-inet ip-addr))))

          (row->event [row]
            (select-keys row [:type
                              :name
                              :source
                              :created-at
                              :tracked-at
                              :profile-id
                              :ip-addr
                              :props
                              :context]))

          (send [events]
            (let [token   (tokens/generate (::main/props cfg)
                                           {:iss "authentication"
                                            :iat (dt/now)
                                            :uid uuid/zero})
                  body    (t/encode {:events events})
                  headers {"content-type" "application/transit+json"
                           "origin" (cf/get :public-uri)
                           "cookie" (u/map->query-string {:auth-token token})}
                  params  {:uri uri
                           :timeout 6000
                           :method :post
                           :headers headers
                           :body body}
                  resp    (http/req! cfg params {:sync? true})]
              (if (= (:status resp) 204)
                true
                (do
                  (l/error :hint "unable to archive events"
                           :resp-status (:status resp)
                           :resp-body (:body resp))
                  false))))

          (mark-as-archived [conn rows]
            (db/exec-one! conn ["update audit_log set archived_at=now() where id = ANY(?)"
                                (->> (map :id rows)
                                     (into-array java.util.UUID)
                                     (db/create-array conn "uuid"))]))]

    (db/with-atomic [conn pool]
      (let [rows   (db/exec! conn [sql:retrieve-batch-of-audit-log])
            xform  (comp (map decode-row)
                         (map row->event))
            events (into [] xform rows)]
        (when-not (empty? events)
          (l/trace :hint "archive events chunk" :uri uri :events (count events))
          (when (send events)
            (mark-as-archived conn rows)
            (count events)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; GC Task
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private sql:clean-archived
  "delete from audit_log
    where archived_at is not null")

(defn- clean-archived
  [{:keys [pool]}]
  (let [result (db/exec-one! pool [sql:clean-archived])
        result (:next.jdbc/update-count result)]
    (l/debug :hint "delete archived audit log entries" :deleted result)
    result))

(defmethod ig/pre-init-spec ::tasks/gc [_]
  (s/keys :req [::db/pool]))

(defmethod ig/init-key ::tasks/gc
  [_ cfg]
  (fn [_]
    (clean-archived cfg)))
