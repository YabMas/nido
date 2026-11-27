(ns nido.ui.catalog-test
  "The scenes ARE the test.

   Two things are pinned here, and between them they are what makes the catalog worth having
   rather than a second place the truth can rot.

   The first is that every registered scene renders. A scene is a literal value and a router
   that goes nowhere, so rendering one needs no ledger, no session registry, no clock and
   nothing stubbed — which is the design's `blocks-render-from-a-value` checked rather than
   asserted. A block that started reading something fails here, in a test that names the scene,
   instead of on a page where it would read as missing data.

   The second is that the registry covers every function the component modules publish. Without
   it the catalog degrades exactly the way documentation does: a block gets added, nobody adds
   its scene, and the sidebar quietly stops being the set of blocks. `every-block-has-a-scene`
   is the claim; `ns-publics` is how it is checked, so adding a public function to a component
   namespace fails this test until it has somewhere to be looked at."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [hiccup2.core :as h]
   [nido.ui.catalog :as catalog]
   [nido.ui.catalog.scenes :as scenes]
   [nido.libraries.ui.wire :as wire]
   [nido.ui.components.ask :as ask]
   [nido.ui.components.row :as row]
   [nido.ui.components.state :as state]))

(def ^:private component-namespaces
  ['nido.ui.components.state 'nido.ui.components.row 'nido.ui.components.ask
   'nido.ui.components.chat 'nido.ui.components.dialog 'nido.ui.components.control])

(def ^:private not-a-block
  "Public vars of a component namespace that are not something to render: the control set's
   rules, which every control scene is drawn in."
  #{'nido.ui.components.control/css})

(defn- blocks []
  (mapcat :blocks (scenes/groups)))

(defn- render-str
  "A scene's markup as a string. `h/html` is what the dashboard renders through, so a scene that
   produces something hiccup cannot compile fails the same way a page would."
  [scene]
  (str (h/html ((:render scene)))))

;; ---------------------------------------------------------------------------
;; Every scene renders

(deftest every-scene-renders-from-its-own-value
  (doseq [{:keys [name scenes]} (blocks)
          {:keys [title] :as scene} scenes]
    (testing (str name " · " title)
      (let [html (try (render-str scene)
                      (catch Throwable t
                        (throw (ex-info (str "scene threw: " name " · " title) {} t))))]
        (is (string? html))
        (is (not (str/blank? html))
            "a scene that renders nothing at all has nothing to show a person")))))

(deftest a-scene-renders-the-same-markup-twice
  ;; A block that read anything outside its arguments — a clock, a registry, an atom — is most
  ;; likely to show it by rendering differently on the second call. Cheap, and it is the one
  ;; check here that could catch a read this test suite does not otherwise isolate.
  (doseq [{:keys [name scenes]} (blocks)
          {:keys [title] :as scene} scenes]
    (testing (str name " · " title)
      (is (= (render-str scene) (render-str scene))))))

(defn- page-state-names
  "The DOM ids and the page signals a scene's markup declares. A registered block seeds its
   signals as JSON nested under its scope and instance, so that pair is the name it owns."
  [html]
  (let [signal-values (map second (re-seq #"data-signals[^=]*=\"([^\"]*)\"" html))]
    (into (set (map #(str "#" (second %)) (re-seq #"\sid=\"([^\"]+)\"" html)))
          (mapcat (fn [v]
                    (if (str/starts-with? v "{&quot;")
                      (for [[scope ids] (json/parse-string (str/replace v "&quot;" "\""))
                            [id _] ids]
                        (str "$" scope "." id))
                      (map #(str "$" (second %)) (re-seq #"(?:\{|, )(\w+): " v)))))
          signal-values)))

(deftest no-two-scenes-share-page-state
  ;; The catalog draws every scene on one page, so a DOM id or a signal two scenes both declare
  ;; is one element or one value between them: a toggle in one scene would open the other, and
  ;; the later declaration would overwrite the earlier scene's literal state.
  (let [owners (reduce (fn [acc [label names]]
                         (reduce #(update %1 %2 (fnil conj []) label) acc names))
                       {}
                       (for [{:keys [name scenes]} (blocks)
                             {:keys [title] :as scene} scenes]
                         [(str name " · " title) (page-state-names (render-str scene))]))]
    (doseq [[n labels] owners :when (> (count labels) 1)]
      (is false (str n " is declared by more than one scene: " (str/join ", " labels))))))

;; ---------------------------------------------------------------------------
;; Every block has one

(deftest the-registry-covers-every-published-block
  (let [covered (into #{} (map :name) (blocks))]
    (doseq [ns-sym component-namespaces]
      (require ns-sym)
      (doseq [[sym _] (ns-publics ns-sym)
              :when (not (not-a-block (symbol (str ns-sym) (str sym))))]
        (testing (str ns-sym "/" sym)
          (is (contains? covered (clojure.core/name sym))
              (str "no scene renders " ns-sym "/" sym
                   " — add one to nido.ui.catalog.scenes, or make the function private")))))))

(deftest every-registered-block-is-a-published-function
  ;; The other direction: a scene naming a function nobody publishes is a scene for something
  ;; that moved or was renamed, and it would keep rendering while the sidebar lied about it.
  (let [published (into #{} (mapcat #(do (require %) (map (comp clojure.core/name key)
                                                          (ns-publics %))))
                        component-namespaces)]
    (doseq [{:keys [name]} (blocks)]
      (is (contains? published name)
          (str name " is in the registry but no component namespace publishes it")))))

(deftest every-block-and-every-scene-says-what-it-is-for
  (doseq [{:keys [name note scenes]} (blocks)]
    (testing name
      (is (not (str/blank? note)) "a block says what it is")
      (is (seq scenes) "a block has at least one scene")
      (doseq [{:keys [title]} scenes]
        (is (not (str/blank? title)) "every scene is named")))))

;; ---------------------------------------------------------------------------
;; The structure each family promises
;;
;; Rendering without throwing is not enough: a block can lose the thing that makes it that
;; block and still produce markup. These are the few structural facts a scene of each family
;; must carry, kept to what identifies the block rather than to how it looks — the looks are
;; what the catalog exists to let a person change.

(defn- scenes-of [block-name]
  (->> (blocks) (filter #(= block-name (:name %))) first :scenes))

(defn- html-of [block-name scene-title]
  (render-str (first (filter #(= scene-title (:title %)) (scenes-of block-name)))))

(deftest a-state-mark-scene-tells-a-pause-from-a-fault
  ;; The two marks a person must never confuse: ‖ is their own pause, and a workstream nothing
  ;; is moving is a fault — it needs them as a system error.
  (let [html (html-of "state-mark" "needs you, running, paused, stalled, stuck, quiet")]
    (is (str/includes? html "class=\"needs\""))
    (is (str/includes? html "st-mark paused"))
    (is (str/includes? html "kind-system-error") "a stall is a fault: it needs you as a system error")
    (is (str/includes? html "st-mark stalled") "a run that will not end is marked ⚠")
    (is (str/includes? html "st-mark live"))
    (is (str/includes? html "scene-empty") "and a quiet row renders no mark at all")))

(deftest a-board-row-scene-renders-a-selectable-row
  (let [html (html-of "board-row" "the ordinary row")]
    (is (str/includes? html "class=\"gate-card\""))
    (is (str/includes? html "checkout is off by a cent"))
    (is (str/includes? html "BR-7285") "the stable tag, which the generated title is not"))
  (is (str/includes? (html-of "board-row" "selected") "gate-card sel"))
  (let [gate (html-of "board-row" "a gate and its criterion")]
    (is (str/includes? gate "gate-criterion"))
    (is (str/includes? gate "pos-you") "a row whose next move is a person's is lit")))

(deftest a-gate-row-scene-previews-its-question
  (let [html (html-of "gate-row" "one question")]
    (is (str/includes? html "gate-prev"))
    (is (str/includes? html "Accept the triage"))
    (is (str/includes? html "needs you")))
  (is (str/includes? (html-of "gate-row" "working") "working…")))

(deftest an-arc-row-scene-marks-what-waits-on-a-person
  (is (str/includes? (html-of "arc-row" "waiting on you") "class=\"needs\""))
  (is (str/includes? (html-of "arc-row" "revisited") "3 visits"))
  (is (str/includes? (html-of "arc-row" "stale") "no longer stands")
      "a stale stage says what happened to its records rather than counting them")
  (is (str/includes? (html-of "arc-row" "skipped") "no record")))

(deftest a-question-card-scene-asks-its-question-and-offers-every-answer
  (let [one (html-of "card" "one question")]
    (is (str/includes? one "class=\"qcard\""))
    (is (str/includes? one "Should the component catalog replace the pages as the interface?"))
    ;; With no submit, the recommended OPTION is the primary — it is the control that gives the
    ;; answer, so it carries the emphasis — and the reply's send button is demoted from
    ;; btn-primary to btn, because two primaries on one decision is the thing ui-principles §4
    ;; forbids. Asserting both is what makes this a check and not a spelling.
    (is (str/includes? one "choice primary") "the recommended answer is the one primary")
    (is (not (str/includes? one "btn btn-primary"))
        "and nothing else on the card is primary beside it")
    (is (str/includes? one "/_catalog/nowhere/") "every POST goes to the route the scene gave it")
    (is (not (str/includes? one "/workstreams/")) "and to no real route"))
  (let [many (html-of "card" "several questions")]
    (is (str/includes? many "qc-nav") "one stepper")
    (is (= 2 (count (re-seq #"class=\"qbtn qbtn-icon qc-mark\"" many))) "one marker per question")
    (is (str/includes? many "data-signals__ifmissing") "the choices are page state")
    (is (= 1 (count (re-seq #"class=\"btn btn-primary\"" many)))
        "one submitting action, and no choice that submits on its own"))
  (let [pending (html-of "card" "pending")]
    (is (str/includes? pending "working…"))
    (is (not (str/includes? pending "/_catalog/nowhere/"))
        "a pending card offers no control for any action"))
  (is (str/includes? (html-of "card" "a failed action") "action-err")))

;; ---------------------------------------------------------------------------
;; The page

(deftest the-catalog-page-renders-in-the-apps-stylesheet-with-datastar
  (let [html (catalog/catalog-page nil)]
    (is (str/includes? html ":root {") "the app's own token block, not a copy")
    (is (str/includes? html (str (h/html (wire/script))))
        "Datastar from the one place a dashboard page loads it from, not a URL of its own")
    (is (str/includes? html ".cat-frame") "its own chrome rules")
    (is (str/includes? html "Components"))
    (is (str/includes? html "Message the agent") "the first group, the chat panel, renders when nothing is selected")))

(deftest the-chat-panel-leads-the-catalog
  (is (= "chat" (:id (first (scenes/groups))))))

(deftest the-page-selects-by-query-string-and-survives-a-bad-one
  (is (str/includes? (catalog/catalog-page "block=ask") "Should the component catalog replace the pages as the interface?"))
  (is (str/includes? (catalog/catalog-page "block=row") "class=\"gate-card\""))
  (testing "an unknown value falls back to the first option rather than rendering nothing"
    (doseq [q [nil "" "block=nope" "surface=chartreuse" "width=enormous" "block=ask&surface=&width="]]
      (let [html (catalog/catalog-page q)]
        (is (str/includes? html "cat-frame")
            (str "no scene rendered for query " (pr-str q)))))))

(deftest a-selector-link-changes-one-field-and-keeps-the-rest
  (let [html (catalog/catalog-page "block=ask&surface=pane&width=narrow")]
    (is (str/includes? html "class=\"cat-frame w-narrow\"") "the width reaches the frame")
    (is (str/includes? html "background:var(--surface-sunken)") "and so does the surface")
    (is (str/includes? html "block=ask&amp;surface=card&amp;width=narrow")
        "the surface link changes the surface and keeps the block and the width")
    (is (str/includes? html "block=state&amp;surface=pane&amp;width=narrow")
        "and a sidebar link changes the block and keeps both axes")))

(deftest the-catalog-styles-nothing-itself
  ;; catalog-renders-what-the-app-renders, as far as a test can reach it. Every rule the catalog
  ;; uses — the app's and its own chrome's — comes from the assembled stylesheet, so neither the page
  ;; nor a scene may style anything: no rule, and no inline style attribute either, because an
  ;; inline declaration on a block is the same defect wearing different syntax.
  (doseq [f ["src/nido/ui/catalog.clj" "src/nido/ui/catalog/scenes.clj"]]
    (testing f
      (let [src (slurp f)]
        ;; `:style "…"` is an inline declaration; `:style :primary` is an action descriptor's
        ;; own vocabulary and `[:style …]` is where the page emits a stylesheet, so the match
        ;; is on the string value alone.
        (is (empty? (re-seq #":style\s+\"" src))
            "a scene that needs a style needs a class, in the stylesheet")
        (is (empty? (re-seq #"--[a-z][a-z-]*\s*:" src))
            "and a custom property it declares is a token block it is starting"))))
  (is (str/includes? (slurp "src/nido/ui/catalog.clj") "nido.ui.registry")
      "the page's rules come from the one stylesheet every chrome emits")
  (doseq [f ["src/nido/ui/catalog.clj" "src/nido/ui/views.clj"]]
    (is (not (str/includes? (slurp f) "cdn.jsdelivr.net"))
        (str f " writes a Datastar URL of its own; the UI library's wire holds the one"))))

;; ---------------------------------------------------------------------------
;; The blocks are reachable at all
;;
;; blocks-are-addressable, at its thinnest: these are vars, called from here, with no page and
;; no screen anywhere in sight. The test would not compile before the split.

(deftest a-block-renders-outside-any-page
  (is (str/includes? (str (h/html (state/origin-badge :notion))) "b-notion"))
  (is (str/includes? (str (h/html (nido.libraries.ui.block/render
                                   row/board-row "w" {:project "nido" :row {:ws-id "w" :origin :notion :label "x"}
                                                      :href "/x"}
                                   {} (fn [& _] "#"))))
                     "href=\"/x\""))
  (is (= "Status — Approved" (state/status-heading {:at :design-approved})))
  (is (= "ask.ws_1.step" (nido.libraries.ui.names/signal (:id ask/card) "ws-1" :step))))

(deftest the-catalog-spans-the-shell-grid
  ;; The stylesheet makes body the dashboard shell's two-column grid (rail, content). The catalog
  ;; has no rail, so unless its root spans both columns its whole layout sits in the 180px rail
  ;; cell and its main column renders a word wide.
  (is (re-find #"\.cat \{[^}]*grid-column:1 / -1" (catalog/catalog-page nil))))
