;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.rpc.commands.auth
  (:require
   [app.auth :as auth]
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.common.uuid :as uuid]
   [app.config :as cf]
   [app.db :as db]
   [app.emails :as eml]
   [app.http.session :as session]
   [app.loggers.audit :as audit]
   [app.main :as-alias main]
   [app.rpc :as-alias rpc]
   [app.rpc.climit :as climit]
   [app.rpc.commands.teams :as teams]
   [app.rpc.doc :as-alias doc]
   [app.rpc.helpers :as rph]
   [app.rpc.queries.profile :as profile]
   [app.tokens :as tokens]
   [app.util.services :as sv]
   [app.util.time :as dt]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]))

(s/def ::email ::us/email)
(s/def ::fullname ::us/not-empty-string)
(s/def ::lang ::us/string)
(s/def ::path ::us/string)
(s/def ::password ::us/not-empty-string)
(s/def ::old-password ::us/not-empty-string)
(s/def ::theme ::us/string)
(s/def ::invitation-token ::us/not-empty-string)
(s/def ::token ::us/not-empty-string)

;; ---- HELPERS

(defn email-domain-in-whitelist?
  "Returns true if email's domain is in the given whitelist or if
  given whitelist is an empty string."
  [domains email]
  (if (or (empty? domains)
          (nil? domains))
    true
    (let [[_ candidate] (-> (str/lower email)
                            (str/split #"@" 2))]
      (contains? domains candidate))))

(def ^:private sql:profile-existence
  "select exists (select * from profile
                   where email = ?
                     and deleted_at is null) as val")

(defn check-profile-existence!
  [conn {:keys [email] :as params}]
  (let [email  (str/lower email)
        result (db/exec-one! conn [sql:profile-existence email])]
    (when (:val result)
      (ex/raise :type :validation
                :code :email-already-exists))
    params))

;; ---- COMMAND: login with password

(defn login-with-password
  [{:keys [::db/pool session] :as cfg} {:keys [email password scope] :as params}]

  (when-not (or (contains? cf/flags :login)
                (contains? cf/flags :login-with-password))
    (ex/raise :type :restriction
              :code :login-disabled
              :hint "login is disabled in this instance"))

  (letfn [(check-password [profile password]
            (when (= (:password profile) "!")
              (ex/raise :type :validation
                        :code :account-without-password
                        :hint "the current account does not have password"))
            (:valid (auth/verify-password password (:password profile))))

          (validate-profile [profile]
            (when-not profile
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when-not (:is-active profile)
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when (:is-blocked profile)
              (ex/raise :type :restriction
                        :code :profile-blocked))
            (when-not (check-password profile password)
              (ex/raise :type :validation
                        :code :wrong-credentials))
            (when-let [deleted-at (:deleted-at profile)]
              (when (dt/is-after? (dt/now) deleted-at)
                (ex/raise :type :validation
                          :code :wrong-credentials)))

            profile)]

    (db/with-atomic [conn pool]
      (let [profile    (->> (profile/retrieve-profile-data-by-email conn email)
                            (validate-profile)
                            (profile/strip-private-attrs)
                            (profile/populate-additional-data conn)
                            (profile/decode-profile-row))

            invitation (when-let [token (:invitation-token params)]
                         (tokens/verify (::main/props cfg) {:token token :iss :team-invitation}))

            ;; If invitation member-id does not matches the profile-id, we just proceed to ignore the
            ;; invitation because invitations matches exactly; and user can't login with other email and
            ;; accept invitation with other email
            response   (if (and (some? invitation) (= (:id profile) (:member-id invitation)))
                         {:invitation-token (:invitation-token params)}
                         (update profile :is-admin (fn [admin?]
                                                     (or admin?
                                                         (let [admins (cf/get :admins)]
                                                           (contains? admins (:email profile)))))))]

        (when (and (nil? (:default-team-id profile))
                   (not= scope "admin"))
          (ex/raise :type :restriction
                    :code :admin-only-profile
                    :hint "can't login with admin-only profile"))

        (-> response
            (rph/with-transform (session/create-fn session (:id profile)))
            (rph/with-meta {::audit/props (audit/profile->props profile)
                            ::audit/profile-id (:id profile)}))))))

(s/def ::scope ::us/string)
(s/def ::login-with-password
  (s/keys :req-un [::email ::password]
          :opt-un [::invitation-token ::scope]))

(sv/defmethod ::login-with-password
  "Performs authentication using penpot password."
  {::rpc/auth false
   ::climit/queue :auth
   ::doc/added "1.15"}
  [cfg params]
  (login-with-password cfg params))

;; ---- COMMAND: Logout

(s/def ::logout
  (s/keys :opt [::rpc/profile-id]))

(sv/defmethod ::logout
  "Clears the authentication cookie and logout the current session."
  {::rpc/auth false
   ::doc/added "1.15"}
  [{:keys [session] :as cfg} _]
  (rph/with-transform {} (session/delete-fn session)))

;; ---- COMMAND: Recover Profile

(defn recover-profile
  [{:keys [::db/pool] :as cfg} {:keys [token password]}]
  (letfn [(validate-token [token]
            (let [tdata (tokens/verify (::main/props cfg) {:token token :iss :password-recovery})]
              (:profile-id tdata)))

          (update-password [conn profile-id]
            (let [pwd (auth/derive-password password)]
              (db/update! conn :profile {:password pwd} {:id profile-id})))]

    (db/with-atomic [conn pool]
      (->> (validate-token token)
           (update-password conn))
      nil)))

(s/def ::token ::us/not-empty-string)
(s/def ::recover-profile
  (s/keys :req-un [::token ::password]))

(sv/defmethod ::recover-profile
  {::rpc/auth false
   ::climit/queue :auth
   ::doc/added "1.15"}
  [cfg params]
  (recover-profile cfg params))

;; ---- COMMAND: Prepare Register

(defn validate-register-attempt!
  [{:keys [::db/pool] :as cfg} params]

  (when-not (contains? cf/flags :registration)
    (if-not (contains? params :invitation-token)
      (ex/raise :type :restriction
                :code :registration-disabled)
      (let [invitation (tokens/verify (::main/props cfg) {:token (:invitation-token params) :iss :team-invitation})]
        (when-not (= (:email params) (:member-email invitation))
          (ex/raise :type :restriction
                    :code :email-does-not-match-invitation
                    :hint "email should match the invitation")))))

  (when-let [domains (cf/get :registration-domain-whitelist)]
    (when-not (email-domain-in-whitelist? domains (:email params))
      (ex/raise :type :validation
                :code :email-domain-is-not-allowed)))

  ;; Don't allow proceed in preparing registration if the profile is
  ;; already reported as spammer.
  (when (eml/has-bounce-reports? pool (:email params))
    (ex/raise :type :validation
              :code :email-has-permanent-bounces
              :hint "looks like the email has one or many bounces reported"))

  ;; Perform a basic validation of email & password
  (when (= (str/lower (:email params))
           (str/lower (:password params)))
    (ex/raise :type :validation
              :code :email-as-password
              :hint "you can't use your email as password")))

(def register-retry-threshold
  (dt/duration "15m"))

(defn- elapsed-register-retry-threshold?
  [profile]
  (let [elapsed (dt/diff (:modified-at profile) (dt/now))]
    (pos? (compare elapsed register-retry-threshold))))

(defn prepare-register
  [{:keys [::db/pool] :as cfg} params]

  (validate-register-attempt! cfg params)

  (let [profile (when-let [profile (profile/retrieve-profile-data-by-email pool (:email params))]
                  (cond
                    (:is-blocked profile)
                    (ex/raise :type :restriction
                              :code :profile-blocked)

                    (and (not (:is-active profile))
                         (elapsed-register-retry-threshold? profile))
                    profile

                    :else
                    (ex/raise :type :validation
                              :code :email-already-exists
                              :hint "profile already exists")))

        params  {:email (:email params)
                 :password (:password params)
                 :invitation-token (:invitation-token params)
                 :backend "penpot"
                 :iss :prepared-register
                 :profile-id (:id profile)
                 :exp (dt/in-future {:days 7})}

        params (d/without-nils params)

        token  (tokens/generate (::main/props cfg) params)]
    (with-meta {:token token}
      {::audit/profile-id uuid/zero})))

(s/def ::prepare-register-profile
  (s/keys :req-un [::email ::password]
          :opt-un [::invitation-token]))

(sv/defmethod ::prepare-register-profile
  {::rpc/auth false
   ::doc/added "1.15"}
  [cfg params]
  (prepare-register cfg params))

;; ---- COMMAND: Register Profile

(defn create-profile
  "Create the profile entry on the database with limited set of input
  attrs (all the other attrs are filled with default values)."
  [conn params]
  (let [id        (or (:id params) (uuid/next))
        props     (-> (audit/extract-utm-params params)
                      (merge (:props params))
                      (merge {:viewed-tutorial? false
                              :viewed-walkthrough? false
                              :nudge {:big 10 :small 1}})
                      (db/tjson))

        password  (if-let [password (:password params)]
                    (auth/derive-password password)
                    "!")

        locale    (:locale params)
        locale    (when (and (string? locale) (not (str/blank? locale)))
                    locale)

        backend   (:backend params "penpot")
        is-demo   (:is-demo params false)
        is-muted  (:is-muted params false)
        is-active (:is-active params false)
        email     (str/lower (:email params))

        params    {:id id
                   :fullname (:fullname params)
                   :email email
                   :auth-backend backend
                   :lang locale
                   :password password
                   :deleted-at (:deleted-at params)
                   :props props
                   :is-active is-active
                   :is-muted is-muted
                   :is-demo is-demo}]
    (try
      (-> (db/insert! conn :profile params)
          (profile/decode-profile-row))
      (catch org.postgresql.util.PSQLException e
        (let [state (.getSQLState e)]
          (if (not= state "23505")
            (throw e)
            (ex/raise :type :validation
                      :code :email-already-exists
                      :cause e)))))))

(defn create-profile-relations
  [conn profile]
  (let [team (teams/create-team conn {:profile-id (:id profile)
                                      :name "Default"
                                      :is-default true})]
    (-> profile
        (profile/strip-private-attrs)
        (assoc :default-team-id (:id team))
        (assoc :default-project-id (:default-project-id team)))))

(defn send-email-verification!
  [conn props profile]
  (let [vtoken (tokens/generate props
                                {:iss :verify-email
                                 :exp (dt/in-future "72h")
                                 :profile-id (:id profile)
                                 :email (:email profile)})
        ;; NOTE: this token is mainly used for possible complains
        ;; identification on the sns webhook
        ptoken (tokens/generate props
                                {:iss :profile-identity
                                 :profile-id (:id profile)
                                 :exp (dt/in-future {:days 30})})]
    (eml/send! {::eml/conn conn
                ::eml/factory eml/register
                :public-uri (cf/get :public-uri)
                :to (:email profile)
                :name (:fullname profile)
                :token vtoken
                :extra-data ptoken})))

(defn register-profile
  [{:keys [conn session] :as cfg} {:keys [token] :as params}]
  (let [claims     (tokens/verify (::main/props cfg) {:token token :iss :prepared-register})
        params     (merge params claims)

        is-active  (or (:is-active params)
                       (not (contains? cf/flags :email-verification))

                       ;; DEPRECATED: v1.15
                       (contains? cf/flags :insecure-register))

        profile    (if-let [profile-id (:profile-id claims)]
                     (profile/retrieve-profile conn profile-id)
                     (->> (assoc params :is-active is-active)
                          (create-profile conn)
                          (create-profile-relations conn)
                          (profile/decode-profile-row)))
        invitation (when-let [token (:invitation-token params)]
                     (tokens/verify (::main/props cfg) {:token token :iss :team-invitation}))]

    ;; If profile is filled in claims, means it tries to register
    ;; again, so we proceed to update the modified-at attr
    ;; accordingly.
    (when-let [id (:profile-id claims)]
      (db/update! conn :profile {:modified-at (dt/now)} {:id id})
      (when-let [collector (::audit/collector cfg)]
        (audit/submit! collector
                       {:type "fact"
                        :name "register-profile-retry"
                        :profile-id id})))

    (cond
      ;; If invitation token comes in params, this is because the
      ;; user comes from team-invitation process; in this case,
      ;; regenerate token and send back to the user a new invitation
      ;; token (and mark current session as logged). This happens
      ;; only if the invitation email matches with the register
      ;; email.
      (and (some? invitation) (= (:email profile) (:member-email invitation)))
      (let [claims (assoc invitation :member-id  (:id profile))
            token  (tokens/generate (::main/props cfg) claims)
            resp   {:invitation-token token}]
        (-> resp
            (rph/with-transform (session/create-fn session (:id profile)))
            (rph/with-meta {::audit/replace-props (audit/profile->props profile)
                            ::audit/profile-id (:id profile)})))

      ;; If auth backend is different from "penpot" means user is
      ;; registering using third party auth mechanism; in this case
      ;; we need to mark this session as logged.
      (not= "penpot" (:auth-backend profile))
      (-> (profile/strip-private-attrs profile)
          (rph/with-transform (session/create-fn session (:id profile)))
          (rph/with-meta {::audit/replace-props (audit/profile->props profile)
                          ::audit/profile-id (:id profile)}))

      ;; If the `:enable-insecure-register` flag is set, we proceed
      ;; to sign in the user directly, without email verification.
      (true? is-active)
      (-> (profile/strip-private-attrs profile)
          (rph/with-transform (session/create-fn session (:id profile)))
          (rph/with-meta {::audit/replace-props (audit/profile->props profile)
                          ::audit/profile-id (:id profile)}))

      ;; In all other cases, send a verification email.
      :else
      (do
        (send-email-verification! conn (::main/props cfg) profile)
        (rph/with-meta profile
          {::audit/replace-props (audit/profile->props profile)
           ::audit/profile-id (:id profile)})))))

(s/def ::register-profile
  (s/keys :req-un [::token ::fullname]))

(sv/defmethod ::register-profile
  {::rpc/auth false
   ::climit/queue :auth
   ::doc/added "1.15"}
  [{:keys [::db/pool] :as cfg} params]
  (db/with-atomic [conn pool]
    (-> (assoc cfg :conn conn)
        (register-profile params))))

;; ---- COMMAND: Request Profile Recovery

(defn request-profile-recovery
  [{:keys [::db/pool] :as cfg} {:keys [email] :as params}]
  (letfn [(create-recovery-token [{:keys [id] :as profile}]
            (let [token (tokens/generate (::main/props cfg)
                                         {:iss :password-recovery
                                          :exp (dt/in-future "15m")
                                          :profile-id id})]
              (assoc profile :token token)))

          (send-email-notification [conn profile]
            (let [ptoken (tokens/generate (::main/props cfg)
                                          {:iss :profile-identity
                                           :profile-id (:id profile)
                                           :exp (dt/in-future {:days 30})})]
              (eml/send! {::eml/conn conn
                          ::eml/factory eml/password-recovery
                          :public-uri (:public-uri cfg)
                          :to (:email profile)
                          :token (:token profile)
                          :name (:fullname profile)
                          :extra-data ptoken})
              nil))]

    (db/with-atomic [conn pool]
      (when-let [profile (profile/retrieve-profile-data-by-email conn email)]
        (when-not (eml/allow-send-emails? conn profile)
          (ex/raise :type :validation
                    :code :profile-is-muted
                    :hint "looks like the profile has reported repeatedly as spam or has permanent bounces."))

        (when-not (:is-active profile)
          (ex/raise :type :validation
                    :code :profile-not-verified
                    :hint "the user need to validate profile before recover password"))

        (when (eml/has-bounce-reports? conn (:email profile))
          (ex/raise :type :validation
                    :code :email-has-permanent-bounces
                    :hint "looks like the email you invite has been repeatedly reported as spam or permanent bounce"))

        (->> profile
             (create-recovery-token)
             (send-email-notification conn))))))

(s/def ::request-profile-recovery
  (s/keys :req-un [::email]))

(sv/defmethod ::request-profile-recovery
  {::rpc/auth false
   ::doc/added "1.15"}
  [cfg params]
  (request-profile-recovery cfg params))


