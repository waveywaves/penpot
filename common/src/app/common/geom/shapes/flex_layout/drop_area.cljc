;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.geom.shapes.flex-layout.drop-area
  (:require
   [app.common.data :as d]
   [app.common.geom.matrix :as gmt]
   [app.common.geom.shapes.common :as gco]
   [app.common.geom.shapes.flex-layout.lines :as fli]
   [app.common.geom.shapes.points :as gpo]
   [app.common.geom.shapes.rect :as gsr]
   [app.common.pages.helpers :as cph]
   [app.common.types.shape.layout :as ctl]))

(defn drop-child-areas
  [frame parent-rect child-bounds index reverse? prev-x prev-y last?]

  (let [col?      (ctl/col? frame)
        row?      (ctl/row? frame)
        [layout-gap-row layout-gap-col] (ctl/gaps frame)

        start-p (gpo/origin child-bounds)

        box-x      (:x start-p)
        box-y      (:y start-p)
        box-width  (gpo/width-points child-bounds)
        box-height (gpo/height-points child-bounds)

        x (if col? (:x parent-rect) prev-x)
        y (if row? (:y parent-rect) prev-y)

        width
        (cond
          (and row? last?)
          (- (+ (:x parent-rect) (:width parent-rect)) x)

          col?
          (:width parent-rect)

          :else
          (+ box-width (- box-x prev-x) (/ layout-gap-col 2)))

        height
        (cond
          (and col? last?)
          (- (+ (:y parent-rect) (:height parent-rect)) y)

          row?
          (:height parent-rect)

          :else
          (+ box-height (- box-y prev-y) (/ layout-gap-row 2)))]

    (if row?
      (let [half-point-width (+ (- box-x x) (/ box-width 2))]
        [(gsr/make-rect x y width height)
         (-> (gsr/make-rect x y half-point-width height)
             (assoc :index (if reverse? (inc index) index)))
         (-> (gsr/make-rect (+ x half-point-width) y (- width half-point-width) height)
             (assoc :index (if reverse? index (inc index))))])
      (let [half-point-height (+ (- box-y y) (/ box-height 2))]
        [(gsr/make-rect x y width height)
         (-> (gsr/make-rect x y width half-point-height)
             (assoc :index (if reverse? (inc index) index)))
         (-> (gsr/make-rect x (+ y half-point-height) width (- height half-point-height))
             (assoc :index (if reverse? index (inc index))))]))))

(defn drop-line-area
  [{:keys [transform-inverse margin-x margin-y] :as frame}
   {:keys [start-p layout-gap-row layout-gap-col num-children line-width line-height] :as line-data}
   prev-x prev-y last?]

  (let [col?      (ctl/col? frame)
        row?      (ctl/row? frame)
        h-center? (and row? (ctl/h-center? frame))
        h-end?    (and row? (ctl/h-end? frame))
        v-center? (and col? (ctl/v-center? frame))
        v-end?    (and row? (ctl/v-end? frame))

        center (gco/center-shape frame)
        start-p (gmt/transform-point-center start-p center transform-inverse)

        line-width
        (if row?
          (:width frame)
          (+ line-width margin-x
             (if row? (* layout-gap-col (dec num-children)) 0)))

        line-height
        (if col?
          (:height frame)
          (+ line-height margin-y
             (if col?
               (* layout-gap-row (dec num-children))
               0)))

        box-x
        (- (:x start-p)
           (cond
             h-center? (/ line-width 2)
             h-end? line-width
             :else 0))

        box-y
        (- (:y start-p)
           (cond
             v-center? (/ line-height 2)
             v-end? line-height
             :else 0))

        x (if row? (:x frame) prev-x)
        y (if col? (:y frame) prev-y)

        width (cond
                (and col? last?)
                (- (+ (:x frame) (:width frame)) x)

                row?
                (:width frame)

                :else
                (+ line-width (- box-x prev-x) (/ layout-gap-col 2)))

        height (cond
                 (and row? last?)
                 (- (+ (:y frame) (:height frame)) y)

                 col?
                 (:height frame)

                 :else
                 (+ line-height (- box-y prev-y) (/ layout-gap-row 2)))]
    (gsr/make-rect x y width height)))

(defn layout-drop-areas
  "Retrieve the layout drop areas to move shapes inside layouts"
  [frame layout-data children]

  (let [reverse? (:reverse? layout-data)
        children (vec (cond->> (d/enumerate children) (not reverse?) reverse))
        lines    (:layout-lines layout-data)]

    (loop [areas        []
           from-idx     0
           prev-line-x  (:x frame)
           prev-line-y  (:y frame)
           lines        (seq lines)]

      (if (empty? lines)
        areas

        (let [current-line (first lines)
              line-area (drop-line-area frame current-line prev-line-x prev-line-y (empty? (rest lines)))
              children  (subvec children from-idx (+ from-idx (:num-children current-line)))

              next-areas
              (loop [areas         areas
                     prev-child-x  (:x line-area)
                     prev-child-y  (:y line-area)
                     children (seq children)]

                (if (empty? children)
                  areas

                  (let [[index [child-bounds _]] (first children)
                        [child-area child-area-start child-area-end]
                        (drop-child-areas frame line-area child-bounds index (not reverse?) prev-child-x prev-child-y (empty? (rest children)))]
                    (recur (conj areas child-area-start child-area-end)
                           (+ (:x child-area) (:width child-area))
                           (+ (:y child-area) (:height child-area))
                           (rest children)))))]

          (recur next-areas
                 (+ from-idx (:num-children current-line))
                 (+ (:x line-area) (:width line-area))
                 (+ (:y line-area) (:height line-area))
                 (rest lines)))))))

(defn get-drop-index
  [frame-id objects position]
  (let [frame       (get objects frame-id)
        position    (gmt/transform-point-center position (gco/center-shape frame) (:transform-inverse frame))
        children    (->> (cph/get-immediate-children objects frame-id)
                         (remove :hidden)
                         (map #(vector (gpo/parent-coords-bounds (:points %) (:points frame)) %)))
        layout-data (fli/calc-layout-data frame children (:points frame))
        drop-areas  (layout-drop-areas frame layout-data children)
        area        (d/seek #(gsr/contains-point? % position) drop-areas)]
    (:index area)))
