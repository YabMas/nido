(ns canvas.libraries.ui
  "Self-spec: `nido.libraries.ui.*` — UI machinery for server-rendered Clojure + Datastar apps.

   Machinery for building visual components, never a set of them: an app hands it values — its
   token values, its block values, a router, an explicit context — and gets text and hiccup back.
   It operates on hiccup data so each app keeps its own markup renderer, and it requires nothing
   babashka does not bundle beyond malli, which its props and action schemas are written in and
   which the app declares; so it runs under babashka and the JVM alike.

   Every module name carries the `libraries-ui-` prefix because fukan pairs a Module to a
   namespace by a dotted suffix: a bare `ui-catalog` would answer to `nido.ui.catalog` and to
   `nido.libraries.ui.catalog` at once. The suffix match runs both ways, so the catalog's own
   namespace is `catalog-page`: `nido.libraries.ui.catalog` would end in `ui.catalog` and answer
   to nido's `ui-catalog` however its own module were named."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [fukan.common.typing.malli]))

(Kind Action
  "One action a block emits, as data: `[method name fields]`, where `method` is :get or :post,
   `name` the keyword the caller's router turns into a URL, and `fields` a map from each posted
   key to a placeholder — `[:signal k schema]` or `[:event k schema]` — naming where its value
   comes from and the malli schema it must satisfy, optionally followed by `{:optional true}`. The
   one place a posted name is spelled."
  [:tuple [:enum :get :post] :keyword [:map-of :keyword [:or [:cat [:= :signal] [:or :keyword [:vector :keyword]] :some [:? [:map [:optional :boolean]]]] [:cat [:= :event] :keyword :some [:? [:map [:optional :boolean]]]]]]])

(Kind Block
  "A block, as an immutable value built once at registry time: its id, a render function over
   props, context and router, the malli schema of its props, its own CSS rules, the actions it
   declares, its morph contract — the parts of its markup the client owns — and its scenes."
  [:map [:id :keyword] [:render :any] [:props :any] [:css :string]
   [:actions [:map-of :keyword Action]] [:morph :map] [:scenes [:vector {:min 1} :map]]])

(Module libraries-ui-style
  "The stylesheet, assembled.

   HIDES the cascade — which @layer each part sits in and in which order — how a token map
   becomes the one :root block, and how CSS is read to check it. A rule an app emits has one of
   two owners: the block whose registration carries it, or the app, which hands over every rule
   no registration carries — the rules of classes its marks, blocks and pages share among them.
   This is the only place they meet, and it meets them once, at registry time, so a fragment
   patched in later is already styled."
  (Operation root-block "The token map as the single :root block, a keyword value reading another token."
    {:signature [:=> [:catn [:tokens :map]] :string]})
  (Operation stylesheet
    "The whole stylesheet for an app: its tokens as the single :root block, the layout rules,
     every registered block's rules and the app's own rules, each in its declared layer."
    {:signature [:=> [:catn [:spec [:map [:tokens :map] [:blocks [:sequential :map]]
                                         [:rules {:optional true} [:maybe :string]]]]] :string]})
  (Operation lint
    "What is wrong with a stylesheet against its tokens: a literal colour, size, spacing, radius,
     shadow or duration where a var(--…) belongs, a var() naming an undeclared token, and a
     declared token nothing reads. Empty when nothing is."
    {:signature [:=> [:catn [:css :string] [:tokens :map]] [:vector :map]]}))

(Module libraries-ui-names
  "Instance-scoped names.

   HIDES how a DOM id, a data-scope/data-part pair and a signal are derived from one instance id,
   so two instances on one page never share one and a server names a block's patch target by the
   same derivation the markup used rather than by retyping it."
  (Operation part-id "The DOM id of one part of one instance: <scope>:<id>:<part>."
    {:signature [:=> [:catn [:scope :keyword] [:id :string] [:part :keyword]] :string]})
  (Operation selector
    "A CSS selector for one part of one instance, its id escaped for querySelector — how a server
     names a block's patch target."
    {:signature [:=> [:catn [:scope :keyword] [:id :string] [:part :keyword]] :string]
     :delegates [part-id]})
  (Operation part-attrs
    "The attrs one part spreads: its id, data-scope and data-part."
    {:signature [:=> [:catn [:scope :keyword] [:id :string] [:part :keyword]] :map]})
  (Operation signal "The path of one signal in an instance's namespace: <scope>.<id>.<k…>."
    {:signature [:=> [:catn [:scope :keyword] [:id :string] [:k [:or :keyword [:sequential :keyword]]]] :string]})
  (Operation signal-root "The keys an instance's whole signal namespace nests under."
    {:signature [:=> [:catn [:scope :keyword] [:id :string]] [:vector :string]]}))

(Module libraries-ui-action
  "Actions as data, and their decoding.

   HIDES how an Action becomes a Datastar expression — the @get/@post spelling, how signal and
   event values reach the body — and how a posted body is parsed. The decoder is derived from the
   same declaration, so an untrusted request body is read by nothing else."
  (Operation expression
    "The Datastar expression that performs an action, its URL from the caller's router."
    {:signature [:=> [:catn [:action :any] [:router :any] [:opts [:? :map]]] :string]
     :delegates [signal]})
  (Operation decode
    "A posted body read against an action's declared fields, as one arm per outcome: the fields,
     each validated against its schema; :unparseable; :missing, carrying the absent key; or
     :invalid, carrying the key and why its schema refused the value."
    {:signature [:=> [:catn [:action :any] [:body [:maybe :string]] [:opts [:? :map]]] [:or [:map {:closed true} [:ok :map]] [:map {:closed true} [:failure [:= :unparseable]]] [:map {:closed true} [:failure [:= :missing]] [:key :keyword]] [:map {:closed true} [:failure [:= :invalid]] [:key :keyword] [:why :some]]]]}))

(Module libraries-ui-block
  "Blocks as values, and the registry an app keeps them in.

   HIDES how a block value is checked when it is built, how its morph contract becomes the
   attributes that keep a server patch from resetting client-owned state, and how a block is
   rendered for one instance over props, context and a router."
  (Operation block "A block value, checked: refuses one missing a part, declaring an action that is not an Action, with no scene, or with a scene that does not satisfy its props schema."
    {:signature [:=> [:catn [:spec :map]] :map]})
  (Operation kit
    "The kit one instance renders with — its names, its actions through the caller's router — for
     a block rendering one part on its own, as a stream patching it in place does."
    {:signature [:=> [:catn [:block :map] [:id :string] [:props :map] [:ctx :map] [:router :any]] :map]
     :delegates [part-attrs expression]})
  (Operation render
    "One instance of a block as hiccup, over props, the explicit context (now, locale) and the
     caller's router; props are checked against the block's schema."
    {:signature [:=> [:catn [:block :map] [:id :string] [:props :map] [:ctx :map] [:router :any]] :any]
     :delegates [kit]})
  (Operation decode-action
    "A request body read against one of a block's declared actions for one instance."
    {:signature [:=> [:catn [:block :map] [:k :keyword] [:body [:maybe :string]] [:id :string]] [:or [:map {:closed true} [:ok :map]] [:map {:closed true} [:failure [:= :unparseable]]] [:map {:closed true} [:failure [:= :missing]] [:key :keyword]] [:map {:closed true} [:failure [:= :invalid]] [:key :keyword] [:why :some]]]]
     :delegates [decode]}))

(Module libraries-ui-wire
  "The Datastar wire.

   HIDES which Datastar bundle — which pinned version, from where — and how a server-sent event
   is spelled. The runtime a block's data- attributes are written against, named once."
  (Operation script "The script element a page loads Datastar with."
    {:signature [:=> [:catn] :any]})
  (Operation patch-elements-event
    "One server-sent event patching markup into a page, by id or appended under a selector."
    {:signature [:=> [:catn [:html :string] [:opts [:? :map]]] :string]})
  (Operation patch-signals-event "One server-sent event merging signals into page state."
    {:signature [:=> [:catn [:json :string]] :string]}))

(Module libraries-ui-catalog-page
  "The component catalog over an app's registry.

   HIDES the catalog's chrome — how a scene is framed, labelled and selected, the rules it is
   drawn with and the query-string names it reads its selection from — and that it writes every
   link and request as a relative path, so it runs unchanged under any prefix an app mounts it at."
  (Operation catalog-page
    "The catalog: a sidebar of the registry's groups, every scene of the selected one rendered in
     the app's stylesheet with Datastar live."
    {:signature [:=> [:catn [:registry :map] [:stylesheet :string] [:query-string [:maybe :string]]] :string]
     :delegates [script render]}))
