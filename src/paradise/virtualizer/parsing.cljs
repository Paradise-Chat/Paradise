(ns ^:virtualizer/skip paradise.virtualizer.parsing
  (:require [virtualizer.worker.dom-shim]
            [virtualizer.protocol :as proto]
            [virtualizer.worker.ast :as ast]
            [virtualizer.worker.pretext :as pretext]
            [paradise.virtualizer.spec :as spec]))

(def !ast-node-cache ast/!ast-node-cache)
(def !deferred-component-cache ast/!deferred-component-cache)
(def !current-event-id ast/!current-event-id)
(def !visible-active-ids ast/!visible-active-ids)
(def !is-scrolling? ast/!is-scrolling?)
(def !pending-defer-check? ast/!pending-defer-check?)
(def !item-revisions ast/!item-revisions)

(def flush-ast-cache! ast/flush-ast-cache!)
(def strip-fns ast/strip-fns)
(def check-deferred-components! ast/check-deferred-components!)
(def extract-first-url spec/extract-first-url)
(def sanitize-cache spec/!sanitize-cache)
(def memoized-sanitize spec/memoized-sanitize)
(def camel-case ast/camel-case)
(def expand-hiccup ast/expand-hiccup)
(def process-props ast/process-props)
(def hiccup->pojo ast/hiccup->pojo)
(def extract-lambda-paths ast/extract-lambda-paths)

(defn build-ast-tree [laid-out]
  (ast/build-ast-tree (spec/->ParadiseTimelineSpec nil) laid-out))

(defn precompile-event-data [e source room-id]
  (proto/precompile-item (spec/->ParadiseTimelineSpec source) e room-id pretext/pretext-api))
