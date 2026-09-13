(ns paradise.binding.streams
  (:require [paradise.binding.matrix :as matrix]
            [paradise.binding.nostr :as nostr]
            ))

;; Should move this to core and place a method inside core
;; to enable the engine to call the main thread and
;; adjust the registration of components.
;; Removal is easy as it is through the registry
;; Addition would need to be treated as a plugin
;; Most runtime plugins won't carry heavy weight
;; as the timeline handling is offloaded from the
;; main thread
;; As such it can further be broken up to scale
;; cleaner as well


(defn init-engine-streams! [engine-id]
  (case (keyword engine-id)
    :matrix (matrix/init!)
    :nostr  (nostr/init!)
    nil))

