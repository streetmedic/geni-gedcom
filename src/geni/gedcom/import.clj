(ns geni.gedcom.import
  (:require [gedcom.core :refer [parse-gedcom]]
            [useful.utils :refer [queue]]
            [useful.seq :refer [glue]]
            [useful.map :refer [merge-in update-each map-to map-vals map-vals-with-keys remove-vals]]
            [geni.gedcom.common :refer [to-geni profile-ids union-ids]]
            [geni.core :as geni]))

(defn import-tree
  "Import a map of profiles and unions. Replace all nodes that have already been assigned ids with
  the id. Return a new map of labels to ids. Note that /profiles/import_tree has a size limit for
  unions and profiles which is 100 by default."
  [token ids tree]
  (let [tree (update-each tree [:profiles :unions]
                          map-vals-with-keys
                          (fn [k v]
                            (get ids k v)))
        results (geni/write "/profiles/import_tree"
                            tree
                            {:access_token token
                             :only_ids 1})]
    (merge-in ids (get results "imported"))))

(def ^:dynamic *max-batch-size* 100)

(defn walk-gedcom
  "Walk GEDCOM records starting at label by walking over the graph of INDI and FAM records.
  Returns a list of the profiles and unions encountered at each step."
  [records label]
  (loop [to-follow (queue [label])
         followed? #{label}
         steps []]
    (if-let [profile-id (first to-follow)]
      (let [union-ids   (remove followed? (union-ids (get records profile-id)))
            unions      (remove-vals (map-to records union-ids) #(< (-> % profile-ids count) 2))
            profile-ids (mapcat profile-ids (vals unions))
            profiles    (map-to records profile-ids)]
        (recur (into (pop to-follow) (remove followed? profile-ids))
               (into followed? (concat union-ids profile-ids))
               (if (every? empty? [unions profiles])
                 steps
                 (conj steps {:unions unions, :profiles profiles}))))
      steps)))

(defn in-batches
  "Combine the steps produced by walk-gedcom into batches of no more than n profiles or unions."
  [n steps]
  (glue merge-in
        (constantly true)
        (fn [batch]
          (some #(< n (count (get batch %)))
                [:unions :profiles]))
        steps))

(defn import-gedcom
  "Import the given GEDCOM file using the Geni API. The provided label identifies yourself in the
  GEDCOM. Token is expected to be a Geni OAuth access token."
  [file label token]
  (let [id (get (geni/read "/profile" {:access_token token}) "id")
        records (map-vals (parse-gedcom file) to-geni)]
    (reduce (partial import-tree token)
            {label id}
            (in-batches *max-batch-size*
                        (walk-gedcom records label)))))
