(ns canvas.claims.ui-library
  "The claims the UI-library design commits nido to — a UI library, nido.libraries.ui, that nido's
   dashboard consumes — declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements it
   is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.libraries.ui :as lib]
            [canvas.strata :as strata]
            [canvas.ui.presentation :as presentation]
            [canvas.ui.surface :as surface]
            [fukan.common.typing.malli]))

(Claim blocks-are-values
  "Every block nido publishes is registered with the UI library as a value carrying its render function, a props schema, its own CSS, the actions it emits, its morph contract and its scenes, and its markup depends only on its props, the explicit context it is handed (now, locale) and the router its caller passes. A registration's CSS is empty where the block's rules stay among ui-stylesheet's app rules, which share classes with the pages and are ordered against them in the app's cascade layer; the rule still has one owner. Because a registration is a def'd value and fukan realizes an Operation only by a function, a block's canvas module states its registrations in prose and declares no Operation for them."
  {:about [strata/ui-blocks surface/ui-components-row surface/ui-components-ask surface/ui-components-chat surface/ui-catalog-scenes lib/libraries-ui-block] :evidence "round"})

(Claim actions-are-data
  "A block declares each action as data — [method name fields], each field a placeholder for a signal or event value ([:signal k] or [:event k]) together with the malli schema that value must satisfy — which the library turns into the Datastar expression through the caller's router; the server decodes a posted block action only with a decoder derived from the same declaration, which returns either the declared fields, each validated against its schema, or a named failure — a body that does not parse, a declared field missing, or a field failing its schema — and a handler is called only with the former. No block namespace writes an '@post(' or '@get(' literal, and a test in nido's suite searches every nido.ui.components namespace for those literals and fails on one; no handler reads a field a block posts by a literal of its own."
  {:about [surface/ui-components-row surface/ui-components-ask surface/ui-components-chat surface/ui-server lib/libraries-ui-action] :evidence "round"})

(Claim names-derive-from-an-instance
  "Every DOM id, signal and patch target a block emits is derived by the library from one instance id the caller gives — ids as <scope>:<id>:<part>, signals in a namespace under the instance — and the server names a block's patch target through the same derivation, so two instances on one page share neither and no target is retyped."
  {:about [strata/ui-blocks surface/ui-components-ask surface/ui-components-chat surface/ui-components-dialog lib/libraries-ui-names] :evidence "round"})

(Claim client-state-is-declared
  "What a block leaves to the client — an open panel, a draft, a busy flag, any seeded signal — is declared in its morph contract, whose signals may be a function of the block's props, and the library emits the attributes that keep a server patch from resetting it: data-preserve-attr and data-ignore-morph on the parts the contract names, and the seeded signals as data-signals__ifmissing on a hidden element placed just before the root part, never on the root, because Datastar evaluates an element's attributes in order, hiccup writes them sorted, and an expression reading a signal nested under one not yet seeded throws once and never runs again. No block writes data-preserve-attr, data-ignore-morph or data-signals__ifmissing itself. A dialog's open state is not client state of this kind: it is the element's own open attribute (dialog-proves-the-groundwork)."
  {:about [strata/ui-blocks surface/ui-components-chat surface/ui-components-dialog lib/libraries-ui-block] :evidence "round"})

(Claim one-token-contract
  "nido's tokens are one map in the data file resources/nido/tokens.edn, declared in no nido.ui namespace, covering colour, type, spacing, radius, shadow and motion; nido.ui.stylesheet reads it and supplies it for the library to emit as the single :root block; every colour, font size, spacing, radius, shadow and duration in any rule nido emits reads a declared token through var(--…), checked by the library's CSS-parsing lint, which also fails on a var() naming an undeclared token and on a declared token nothing reads."
  {:about [surface/ui-stylesheet strata/ui-visual-language surface/shell lib/libraries-ui-style] :evidence "round"})

(Claim stylesheet-assembled-at-registry-time
  "Every rule nido emits has one owner: a rule a block's registration carries is that block's, and every other — the state marks' included — is ui-stylesheet's. ui-registry makes the library's stylesheet call once, not per request, over the token values and rules ui-stylesheet supplies and every registration in nido's registry; the library assembles them with its own layout rules in the declared cascade layers. Every nido chrome that emits a stylesheet emits the text ui-registry holds, so a fragment Datastar patches in later is already styled."
  {:about [surface/shell surface/ui-catalog surface/ui-registry surface/ui-stylesheet lib/libraries-ui-style] :evidence "round"})

(Claim library-owns-the-wire
  "The Datastar bundle, the spelling of every SSE event and the catalog's chrome belong to the UI library: no namespace under nido.ui names the bundle, writes an 'event: datastar-' line or draws a catalog frame, label or selector."
  {:about [surface/ui-server surface/shell surface/ui-catalog lib/libraries-ui-wire lib/libraries-ui-catalog-page] :evidence "round"})

(Claim catalog-is-the-library-over-nidos-registry
  "/_catalog is the library's catalog over nido's registry, in nido's stylesheet, both as ui-registry holds them. The registry holds every block registration of nido's component namespaces with the scenes that registration carries, and, for each public function of nido.ui.components.state, the scenes ui-catalog-scenes supplies, which call that function — it stays a plain function, not a registration. Every scene calls the block or function the dashboard calls, over literal values, with any router it hands a block one that goes nowhere. The library's block refuses a registration with no scene, and a public block registration or public function of a component namespace without a scene fails nido's test suite."
  {:about [surface/ui-catalog surface/catalog-page surface/ui-catalog-scenes surface/ui-registry surface/ui-components-state lib/libraries-ui-catalog-page] :evidence "round"})

(Claim dialog-proves-the-groundwork
  "The dialog is a native <dialog> opened and closed by command/commandfor, so the platform owns focus, Escape and the top layer and whether it is open lives in the DOM alone; its parts carry data-scope and data-part with presence attributes for state, its part ids and the signal namespace its registration declares both derive from one instance id through the library's derivation, so its title labels it, its slots are render functions handed the attrs map they spread, and its scenes pass a browser-run test that finds it by role and name and closes it with Escape. It is always modal: a dialog's commands are show-modal, close and request-close, so a non-modal dialog would need a script to open it; which control closed it is its one signal, returned. It is nido's block, internal to nido: the library exports no dialog."
  {:about [surface/ui-components-dialog] :evidence "round"})

(Claim presentation-frames-the-projects-app
  "The Presentation slice shows the focused project's catalog as that project's own running app serves it — a live session's app — the newest of the project's sessions whose app answers — at the path the project declares as :catalog-path on its projects.edn entry, or the dashboard itself for nido — framed through a forward under the dashboard's origin; with no declared catalog or no app up it shows a notice saying which, never an error. The dashboard hands forward every request under the slice's prefix, GET or POST, and forward sends the target the request's method, path, query string and body as they came; the catalog page the library returns writes its links and requests as relative paths, so it runs unchanged under the forward's prefix."
  {:about [presentation/ui-presentation presentation/forward surface/presentation-page surface/handle-request lib/libraries-ui-catalog-page] :evidence "round"})

(Claim rows-say-states-through-marks
  "A row says a workstream's state, the needs-you mark included, only by calling a function of nido.ui.components.state; no row writes a mark's glyph, class or wording itself."
  {:about [strata/ui-state-marks surface/ui-components-row surface/ui-components-state] :evidence "round"})

(Claim page-query-names-shared-by-literal
  "nido.ui.views writes the query names of the URLs its pages link to as string literals, and nido.ui.view-state reads each one back by a string literal of its own."
  {:about [surface/ui-views surface/ui-view-state] :evidence "round"})

(Claim library-is-a-band
  "The UI library lives in nido's codebase under nido.libraries.ui, as the Library band canvas/bands.clj declares: it depends on no other nido band, and only Surface and Tasks may depend on it. It operates on hiccup data, requires nothing babashka does not bundle beyond malli, which nido declares in bb.edn and which its props and action schemas are written in, and its own tests in nido's suite exercise it through nothing but its public functions."
  {:about [lib/libraries-ui-style lib/libraries-ui-block lib/libraries-ui-wire surface/ui-server] :evidence "round"})
