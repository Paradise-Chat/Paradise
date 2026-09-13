(ns paradise.shared.sci-runner.virtualizer
  (:require
   [sci.core :as sci]
   [cljs-workers.worker :as worker]
   [cljs-workers.mesh :as mesh]
   [cljs-workers.core :as main]
   [paradise.shared.utils.macros :refer [expose-ns]]
   [paradise.virtualizer.state :as state]
   [paradise.shared.client.registry :as registry]
   [paradise.shared.sci-runner.factory :as factory]
   [paradise.shared.sci-runner.shared]
   ["@capacitor/core" :refer [Capacitor]]
   ["@capacitor/browser" :refer [Browser] :as browser]
   [clojure.string]
   [sci.configs.reagent.reagent :as sci-reagent]
   [sci.configs.re-frame.re-frame :as sci-re-frame]

   [cljs.core.async]
   [cljs.core.async.interop]
   [cljs.core.async.impl.dispatch]
   [cljs.core.async.impl.ioc-helpers]

   [taoensso.timbre]
   [paradise.shared.utils.logger]
   [paradise.ui.auth.events]
   [paradise.ui.container.base]
   [paradise.ui.container.call.call-view]
   [paradise.ui.container.call.call-container]
   [paradise.ui.container.call.events]
   [paradise.ui.container.members]
   [paradise.ui.container.pins]
   [paradise.ui.container.reusable]
   [paradise.ui.container.search]
   [paradise.ui.container.timeline.components]
   [paradise.ui.container.timeline.base]
   [paradise.ui.container.timeline.item]
   [paradise.ui.input.autocomplete]
   [paradise.ui.input.base]
   [paradise.ui.input.drafts]
   [paradise.ui.input.emotes]
   [paradise.ui.input.composer]
   [paradise.ui.navigation.rooms.entry]
   [paradise.ui.navigation.rooms.room-summary]
   [paradise.ui.navigation.rooms.room-list]
   [paradise.ui.navigation.spaces.bar]
   [paradise.ui.overlays.invites]
   [paradise.ui.overlays.lightbox]
   [paradise.ui.overlays.notifications]
   [paradise.ui.overlays.profiles]
   [paradise.ui.overlays.quick-switcher]
   [paradise.ui.overlays.reactions]
   [paradise.ui.overlays.settings.base]
   [paradise.ui.global]
   [paradise.shared.utils.helpers]
   [paradise.media.component]
   [paradise.shared.utils.svg]))

(defonce !current-eval-plugin (atom nil))

(def log-info-macro (sci/new-macro-var 'info (fn [form _env & args] (list* 'taoensso.timbre/plugin-info @!current-eval-plugin (:line (meta form)) args))))
(def log-warn-macro (sci/new-macro-var 'warn (fn [form _env & args] (list* 'taoensso.timbre/plugin-warn @!current-eval-plugin (:line (meta form)) args))))
(def log-error-macro (sci/new-macro-var 'error (fn [form _env & args] (list* 'taoensso.timbre/plugin-error @!current-eval-plugin (:line (meta form)) args))))
(def log-debug-macro (sci/new-macro-var 'debug (fn [form _env & args] (list* 'taoensso.timbre/plugin-debug @!current-eval-plugin (:line (meta form)) args))))

(defn safe-reg-slot-item [slot-id item]
  (registry/reg-slot-item slot-id (assoc item :plugin-id @!current-eval-plugin)))

(def virtualizer-namespaces
  {'capacitor.browser {'Browser Browser}
   'capacitor.core    {'Capacitor Capacitor}

   'cljs-workers.worker {'register worker/register}
   'cljs-workers.core   {'do-with-pool! main/do-with-pool!}
   'cljs-workers.mesh   {'do-with-thread! mesh/do-with-thread!}

   'paradise.shared.sci-runner.ui (expose-ns paradise.shared.sci-runner.virtualizer)
   'paradise.shared.client.registry  {'!components registry/!components
                                      'reg-slot-item safe-reg-slot-item
                                      '!active-overrides registry/!active-overrides
                                      'remove-plugin-overrides! registry/remove-plugin-overrides!
                                      'get-slot registry/get-slot}

   'paradise.shared.client.state    {'!config (atom {})
                                     '!engine-pool (atom nil)}

   'taoensso.timbre {'info  log-info-macro
                     'warn  log-warn-macro
                     'error log-error-macro
                     'debug log-debug-macro
                     'plugin-info  paradise.shared.utils.logger/plugin-info
                     'plugin-warn  paradise.shared.utils.logger/plugin-warn
                     'plugin-error paradise.shared.utils.logger/plugin-error
                     'plugin-debug paradise.shared.utils.logger/plugin-debug}

   'paradise.media.component (expose-ns paradise.media.component)
   'paradise.shared.sci-runner.virtualizer (expose-ns paradise.shared.sci-runner.virtualizer)
   'paradise.shared.utils.helpers (expose-ns paradise.shared.utils.helpers)
   'paradise.shared.utils.macros (expose-ns paradise.shared.utils.macros)
   'paradise.shared.utils.svg (expose-ns paradise.shared.utils.svg)
   'paradise.ui.auth.events (expose-ns paradise.ui.auth.events)
   'paradise.ui.container.base (expose-ns paradise.ui.container.base)
   'paradise.ui.container.call.call-container (expose-ns paradise.ui.container.call.call-container)
   'paradise.ui.container.call.call-view (expose-ns paradise.ui.container.call.call-view)
   'paradise.ui.container.call.events (expose-ns paradise.ui.container.call.events)
   'paradise.ui.container.members (expose-ns paradise.ui.container.members)
   'paradise.ui.container.pins (expose-ns paradise.ui.container.pins)
   'paradise.ui.container.reusable (expose-ns paradise.ui.container.reusable)
   'paradise.ui.container.search (expose-ns paradise.ui.container.search)
   'paradise.ui.container.timeline.base (expose-ns paradise.ui.container.timeline.base)
   'paradise.ui.container.timeline.components (expose-ns paradise.ui.container.timeline.components)
   'paradise.ui.container.timeline.item (expose-ns paradise.ui.container.timeline.item)
   'paradise.ui.global (expose-ns paradise.ui.global)
   'paradise.ui.input.autocomplete (expose-ns paradise.ui.input.autocomplete)
   'paradise.ui.input.base (expose-ns paradise.ui.input.base)
   'paradise.ui.input.composer (expose-ns paradise.ui.input.composer)
   'paradise.ui.input.drafts (expose-ns paradise.ui.input.drafts)
   'paradise.ui.input.emotes (expose-ns paradise.ui.input.emotes)
   'paradise.ui.navigation.rooms.entry (expose-ns paradise.ui.navigation.rooms.entry)
   'paradise.ui.navigation.rooms.room-list (expose-ns paradise.ui.navigation.rooms.room-list)
   'paradise.ui.navigation.rooms.room-summary (expose-ns paradise.ui.navigation.rooms.room-summary)
   'paradise.ui.navigation.spaces.bar (expose-ns paradise.ui.navigation.spaces.bar)
   'paradise.ui.overlays.invites (expose-ns paradise.ui.overlays.invites)
   'paradise.ui.overlays.lightbox (expose-ns paradise.ui.overlays.lightbox)
   'paradise.ui.overlays.notifications (expose-ns paradise.ui.overlays.notifications)
   'paradise.ui.overlays.profiles (expose-ns paradise.ui.overlays.profiles)
   'paradise.ui.overlays.quick-switcher (expose-ns paradise.ui.overlays.quick-switcher)
   'paradise.ui.overlays.reactions (expose-ns paradise.ui.overlays.reactions)
   'paradise.ui.overlays.settings.base (expose-ns paradise.ui.overlays.settings.base)})

(def virtualizer-context
  (factory/build-context
   :virtualizer
   {:!current-plugin         !current-eval-plugin
    :!overrides              state/!worker-overrides
    :!components             state/!worker-components
    :!slots                  nil
    :!virtualizer-pool       nil}
   virtualizer-namespaces))


(defn eval-virtualizer-plugin! [plugin-id code-str]
  (reset! !current-eval-plugin plugin-id)
  (try
    (sci/eval-string* virtualizer-context code-str)
    {:status :success}
    (catch :default e
      (js/console.error (str "Virtualizer Plugin Eval Error [" plugin-id "]:") e)
      {:status :error
       :error (ex-message e)})))
