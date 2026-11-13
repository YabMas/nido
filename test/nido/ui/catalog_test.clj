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
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [hiccup2.core :as h]
   [nido.ui.catalog :as catalog]
   [nido.ui.catalog.scenes :as scenes]
   [nido.ui.datastar :as datastar]
   [nido.ui.components.ask :as ask]
   [nido.ui.components.row :as row]
   [nido.ui.components.state :as state]))

(def ^:private component-namespaces
  ['nido.ui.components.state 'nido.ui.components.row 'nido.ui.components.ask
   'nido.ui.components.chat])

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

;; ---------------------------------------------------------------------------
;; Every block has one

(deftest the-registry-covers-every-published-block
  (let [covered (into #{} (map :name) (blocks))]
    (doseq [ns-sym component-namespaces]
      (require ns-sym)
      (doseq [[sym _] (ns-publics ns-sym)]
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
  ;; The two glyphs a person must never confuse: ‖ is their own pause and ⚠ is a workstream
  ;; nothing is moving and nothing is asking about.
  (let [html (html-of "state-mark" "running, paused, stalled, quiet")]
    (is (str/includes? html "st-mark paused"))
    (is (str/includes? html "st-mark stalled"))
    (is (str/includes? html "st-mark live"))
    (is (str/includes? html "cat-empty") "and a quiet row renders no mark at all")))

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
  (is (str/includes? (html-of "arc-row" "waiting on you") "arc-wait"))
  (is (str/includes? (html-of "arc-row" "revisited") "3 visits"))
  (let [stale (html-of "arc-row" "stale, and skipped")]
    (is (str/includes? stale "no longer stands")
        "a stale stage says what happened to its records rather than counting them")
    (is (str/includes? stale "no record"))))

(deftest a-question-card-scene-asks-its-question-and-offers-every-answer
  (let [one (html-of "question-card" "one question")]
    (is (str/includes? one "class=\"qcard\""))
    (is (str/includes? one "Approve the component-catalog design?"))
    ;; With no submit, the recommended OPTION is the primary — it is the control that gives the
    ;; answer, so it carries the emphasis — and the reply's send button is demoted from
    ;; btn-primary to btn, because two primaries on one decision is the thing ui-principles §4
    ;; forbids. Asserting both is what makes this a check and not a spelling.
    (is (str/includes? one "qc-opt primary") "the recommended answer is the one primary")
    (is (not (str/includes? one "btn btn-primary"))
        "and nothing else on the card is primary beside it")
    (is (str/includes? one "/_catalog/nowhere/") "every POST goes to the route the scene gave it")
    (is (not (str/includes? one "/workstreams/")) "and to no real route"))
  (let [many (html-of "question-card" "several questions")]
    (is (str/includes? many "qc-nav") "one stepper")
    (is (= 2 (count (re-seq #"class=\"qc-mark\"" many))) "one marker per question")
    (is (str/includes? many "data-signals__ifmissing") "the choices are page state")
    (is (= 1 (count (re-seq #"class=\"btn btn-primary\"" many)))
        "one submitting action, and no choice that submits on its own"))
  (let [pending (html-of "question-card" "pending")]
    (is (str/includes? pending "working…"))
    (is (not (str/includes? pending "/_catalog/nowhere/"))
        "a pending card offers no control for any action"))
  (is (str/includes? (html-of "question-card" "a failed action") "action-err")))

;; ---------------------------------------------------------------------------
;; The page

(deftest the-catalog-page-renders-in-the-apps-stylesheet-with-datastar
  (let [html (catalog/catalog-page nil)]
    (is (str/includes? html ":root {") "the app's own token block, not a copy")
    (is (str/includes? html (str (h/html (datastar/datastar-script))))
        "Datastar from the one place a dashboard page loads it from, not a URL of its own")
    (is (str/includes? html ".cat-frame") "its own chrome rules")
    (is (str/includes? html "Components"))
    (is (str/includes? html "Ask for a change…") "the first group, the chat panel, renders when nothing is selected")))

(deftest the-chat-panel-leads-the-catalog
  (is (= "chat" (:id (first (scenes/groups))))))

(deftest the-page-selects-by-query-string-and-survives-a-bad-one
  (is (str/includes? (catalog/catalog-page "block=ask") "Approve the component-catalog design?"))
  (is (str/includes? (catalog/catalog-page "block=row") "class=\"gate-card\""))
  (testing "an unknown value falls back to the first option rather than rendering nothing"
    (doseq [q [nil "" "block=nope" "surface=chartreuse" "width=enormous" "block=ask&surface=&width="]]
      (let [html (catalog/catalog-page q)]
        (is (str/includes? html "cat-frame")
            (str "no scene rendered for query " (pr-str q)))))))

(deftest a-selector-link-changes-one-field-and-keeps-the-rest
  (let [html (catalog/catalog-page "block=ask&surface=pane&width=narrow")]
    (is (str/includes? html "cat-frame s-sunken w-narrow") "the selection reaches the frame")
    (is (str/includes? html "block=ask&amp;surface=card&amp;width=narrow")
        "the surface link changes the surface and keeps the block and the width")
    (is (str/includes? html "block=state&amp;surface=pane&amp;width=narrow")
        "and a sidebar link changes the block and keeps both axes")))

(deftest the-catalog-styles-nothing-itself
  ;; catalog-renders-what-the-app-renders, as far as a test can reach it. Every rule the catalog
  ;; uses — the app's and its own chrome's — comes from nido.ui.stylesheet, so neither the page
  ;; nor a scene may style anything: no rule, and no inline style attribute either, because an
  ;; inline declaration on a block is the same defect wearing different syntax.
  (doseq [f ["src/nido/ui/catalog.clj" "src/nido/ui/catalog/scenes.clj"]]
    (testing f
      (let [src (slurp f)]
        ;; `:style "…"` is an inline declaration; `:style :primary` is an action descriptor's
        ;; own vocabulary and `[:style …]` is where the page emits a stylesheet, so the match
        ;; is on the string value alone.
        (is (empty? (re-seq #":style\s+\"" src))
            "a scene that needs a style needs a class, in nido.ui.stylesheet")
        (is (empty? (re-seq #"--[a-z][a-z-]*\s*:" src))
            "and a custom property it declares is a token block it is starting"))))
  (is (str/includes? (slurp "src/nido/ui/catalog.clj") "nido.ui.stylesheet")
      "the page's rules come from the one module that holds them")
  (doseq [f ["src/nido/ui/catalog.clj" "src/nido/ui/views.clj"]]
    (is (not (str/includes? (slurp f) "cdn.jsdelivr.net"))
        (str f " writes a Datastar URL of its own; nido.ui.datastar holds the one"))))

;; ---------------------------------------------------------------------------
;; The blocks are reachable at all
;;
;; blocks-are-addressable, at its thinnest: these are vars, called from here, with no page and
;; no screen anywhere in sight. The test would not compile before the split.

(deftest a-block-renders-outside-any-page
  (is (str/includes? (str (h/html (state/origin-badge :notion))) "b-notion"))
  (is (str/includes? (str (h/html (row/board-row "nido" {:ws-id "w" :origin :notion :label "x"}
                                                 {:href "/x"})))
                     "href=\"/x\""))
  (is (= "Status — Approved" (state/status-heading {:at :design-approved})))
  (is (= "ws_1" (ask/card-signal "ws-1"))))

(deftest the-catalog-spans-the-shell-grid
  ;; The stylesheet makes body the dashboard shell's two-column grid (rail, content). The catalog
  ;; has no rail, so unless its root spans both columns its whole layout sits in the 180px rail
  ;; cell and its main column renders a word wide.
  (is (re-find #"\.cat \{[^}]*grid-column:1 / -1" (catalog/catalog-page nil))))
