(ns nido.ui.stylesheet
  "nido's visual language as the UI library takes it: the token values, and every class rule no
   block registration carries.

   HIDES where the token values are read from and which rules are nido's own — the rules of the
   state marks, of the classes blocks and pages share, and of what only a page names. A block's
   own rules travel in its registration; everything else is here, so every rule nido emits has
   one owner. Nothing here assembles a stylesheet: `nido.ui.registry` hands these to the library
   beside the registrations, once, and every chrome emits what comes back.

   The token values are data, in resources/nido/tokens.edn, and no namespace under nido.ui states
   one. Every declaration here reads a token through var(--…), which the library's lint checks."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;; ---------------------------------------------------------------------------
;; The rules

(def ^:private app-css
  (str
   "
        *, *::before, *::after { box-sizing: border-box; }
        /* Scrollbars: a thin thumb in the border hue on no track, so a scrolling pane reads as
           the surface it sits on rather than as the browser's grey chrome. */
        * { scrollbar-width: thin; scrollbar-color: var(--border-strong) transparent; }
        @supports not (scrollbar-color: auto) {
          ::-webkit-scrollbar { width: 8px; height: 8px; }
          ::-webkit-scrollbar-track, ::-webkit-scrollbar-corner { background: transparent; }
          ::-webkit-scrollbar-thumb { background: var(--border-strong); border-radius: var(--radius-4);
                                      border: 2px solid transparent; background-clip: padding-box; }
          ::-webkit-scrollbar-thumb:hover { background-color: var(--text-faint); }
        }
        body { font-family: var(--font-prose);
               font-size:var(--fs-md); line-height: 1.6; color: var(--text);
               background: var(--bg); margin: 0; padding: var(--space-20) var(--space-40); }
        a { color: var(--accent); text-decoration: none; }
        a:hover { text-decoration: underline; }
        h1 { font-size:var(--fs-lg); color: var(--text-strong); margin: 0 0 var(--space-8); }
        h2 { font-size:var(--fs-md); color: var(--text); margin: var(--space-24) 0 var(--space-8); }
        h3 { font-size:var(--fs-sm); color: var(--text-muted); margin: var(--space-16) 0 var(--space-4); }
        table { border-collapse: collapse; width: 100%; }
        th, td { padding: var(--space-6) var(--space-12); text-align: left; border-bottom: 1px solid var(--border); }
        th { color: var(--text-muted); font-weight: normal; font-size:var(--fs-xs); text-transform: uppercase; }
        .card { background: var(--surface); border: 1px solid var(--border); border-radius: var(--radius-6);
                padding: var(--space-16); margin: var(--space-8) 0; }
        .meta { color: var(--text-faint); font-size:var(--fs-xs); }
        .empty { color: var(--text-faint); font-style: italic; }
        @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.5; } }
        .pulse { animation: pulse var(--dur-2000) ease-in-out infinite; }
        .actions { display: flex; gap: var(--space-6); flex-wrap: wrap; }
        .btn { background: var(--control-bg); color: var(--text); border: 1px solid var(--control-bg-hover);
               padding: var(--space-4) var(--space-10); border-radius: var(--radius-3); font-size:var(--fs-xs);
               cursor: pointer; font-family: inherit; }
        .btn:hover { background: var(--control-bg-hover); }
        .btn-primary { background: var(--accent-bg); border-color: var(--accent-border); color: var(--accent-strong); }
        .btn-primary:hover { background: var(--accent-border); }
        .btn-danger { background: var(--failed-bg); border-color: var(--failed-border); color: var(--failed-strong); }
        .btn-danger:hover { background: var(--failed-border); }
        .app-state { padding: var(--space-2) var(--space-8); border-radius: var(--radius-3); font-size:var(--fs-xs);
                     text-transform: uppercase; letter-spacing: 0.04em; }
        .app-state-running { background: var(--ok-bg); color: var(--ok); }
        .app-state-idle { background: var(--waiting-bg); color: var(--waiting); }
        .app-state-starting, .app-state-stopping, .app-state-restarting
          { background: var(--working-bg); color: var(--working);
            animation: pulse var(--dur-1500) ease-in-out infinite; }
        .app-state-failed { background: var(--failed-bg); color: var(--failed); }
        .app-state-dormant { background: var(--dormant-bg); color: var(--dormant); }
        .error-msg { color: var(--failed); font-size:var(--fs-xs); margin-top: var(--space-4);
                     max-width: 420px; overflow: hidden; text-overflow: ellipsis;
                     white-space: nowrap; }
        .action-err { color: var(--failed); background: var(--failed-bg); border: 1px solid var(--failed-border);
                      border-radius: var(--radius-4); padding: var(--space-8) var(--space-10); margin-top: var(--space-16);
                      font-size:var(--fs-xs); }
        .mono, code, pre, kbd { font-family: var(--font-mono); }
        td, .meta { font-variant-numeric: tabular-nums; }
        .badge { display:inline-flex; align-items:center; justify-content:center;
                 width:18px; height:18px; border-radius:var(--radius-4); font-size:var(--fs-xs); font-weight:bold; }
        .b-notion{ background:var(--src-notion-bg); color:var(--src-notion); } .b-github{ background:var(--src-github-bg); color:var(--src-github); }
        .b-slack{ background:var(--src-slack-bg); color:var(--src-slack); } .b-scratch{ background:var(--src-scratch-bg); color:var(--src-scratch); }
        .gate-wrap { display:grid; grid-template-columns: 38% 62%; min-height:80vh; }
        .queue-col { border-right:1px solid var(--border); display:flex; flex-direction:column;
                     min-width:0; min-height:0; }
        .pickup { margin:var(--space-12) var(--space-16) var(--space-10); padding:var(--space-12) var(--space-14); border:1px solid var(--border);
                  border-radius:var(--radius-6); background:var(--surface); }
        .pickup-label { color:var(--text-strong); font-size:var(--fs-xs); text-transform:uppercase; letter-spacing:0.04em; margin-bottom:var(--space-6); }
        .pickup-row { display:flex; gap:var(--space-6); }
        .pickup input { flex:1; box-sizing:border-box; background:var(--surface-sunken); border:1px solid var(--border);
                        border-radius:var(--radius-4); color:var(--text-strong); font:inherit; font-size:var(--fs-xs); padding:var(--space-6) var(--space-9); }
        .pickup input::placeholder { color:var(--text-muted); }
        .pickup input:focus { outline:none; border-color:var(--accent-border); }
        .pickup .btn { color:var(--text-strong); }
        .pickup-result { margin-top:var(--space-2); }
        .inbox { overflow:auto; flex:1; }
        .gate-card { display:block; padding:var(--space-10) var(--space-16); border-bottom:1px solid var(--border-subtle); color:inherit; }
        .gate-card:hover { background:var(--surface-hover); text-decoration:none; }
        .gate-card.sel { background:var(--surface-selected); }
        .gate-top { display:flex; align-items:center; gap:var(--space-8); }
        .row-st { flex:none; width:12px; display:inline-flex; align-items:center; justify-content:center; }
        .gate-top .lbl { flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:var(--text-strong); }
        .needs { display:inline-block; width:7px; height:7px; border-radius:50%; background:var(--needs); box-shadow:var(--glow-needs); }
        .gate-tag { color:var(--text-muted); font-family:var(--font-mono); font-size:var(--fs-xs); margin:var(--space-2) 0 0 var(--space-26);
                    white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .pane-tag { color:var(--text-muted); font-family:var(--font-mono); font-size:var(--fs-xs); margin:-6px 0 var(--space-8); }
        .pane-tag-id { user-select:all; }
        .gate-sub { display:flex; align-items:center; gap:var(--space-8); color:var(--text-faint); font-size:var(--fs-xs); margin:var(--space-3) 0 0 var(--space-26); }
        .gate-sub span:last-child { flex:1; min-width:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .gate-prev { color:var(--text-faint); font-size:var(--fs-xs); margin:var(--space-5) 0 0 var(--space-26);
                     white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .gate-err { color:var(--failed); font-size:var(--fs-xs); margin:var(--space-4) 0 0 var(--space-26); }
        /* Agent prose rendered inside the card (md/render) sits in the card's own type, without
           the bordered box .md draws on its own. These override the shared .md, so they stay
           here: in the ask's own rules, below this layer, they would lose to it. */
        .qc-ctx > .md { font-size:var(--fs-md); color:var(--text); margin:0 0 var(--space-8);
                        max-width:var(--measure); }
        .qcard .md { font:inherit; color:inherit; background:none; border:none; border-radius:0;
                     padding:0; line-height:inherit; }
        .qcard .md p { margin:0 0 var(--space-6); }
        .qcard .md ul, .qcard .md ol { margin:var(--space-2) 0 var(--space-6); padding-left:var(--space-20); }
        .qcard .md li { margin:0 0 var(--space-3); }
        .qcard .md > :last-child, .qcard .md li > :last-child { margin-bottom:0; }
        .ans-row .btn { font-size:var(--fs-sm); padding:var(--space-6) var(--space-12); text-align:left; }
        .ans-body .chip { margin-left:0; }
        .md h1 { font-size:var(--fs-lg); } .md h2 { font-size:var(--fs-md); } .md h3, .md h4 { font-size:var(--fs-sm); }
        .chip { padding:0 var(--space-7); border-radius:var(--radius-3); font-size:var(--fs-xs); text-transform:uppercase; }
        .c-triage{ background:var(--needs-bg); color:var(--needs); } .c-ready{ background:var(--ok-bg); color:var(--ok); }
        .c-in-progress{ background:var(--working-bg); color:var(--working); }
        .c-conf-high{ background:var(--ok-bg); color:var(--ok); } .c-conf-medium{ background:var(--warning-bg); color:var(--warning); }
        .c-conf-low{ background:var(--failed-bg); color:var(--failed); }
        .c-det-bug{ background:var(--warning-bg); color:var(--warning); } .c-det-not-a-bug{ background:var(--neutral-bg); color:var(--text-muted); }
        .c-det-needs-info{ background:var(--working-bg); color:var(--working); }
        .report-meta { margin:0 0 var(--space-10); display:flex; gap:var(--space-6); flex-wrap:wrap; }
        details.trail { margin-top:var(--space-14); } details.trail summary { cursor:pointer; color:var(--text-muted); font-size:var(--fs-xs); }
        .c-rv-ok{ background:var(--ok-bg); color:var(--ok); } .c-rv-warn{ background:var(--warning-bg); color:var(--warning); }
        .c-rv-bad{ background:var(--failed-bg); color:var(--failed); } .c-rv-run{ background:var(--working-bg); color:var(--working); }
        .c-rv-neutral{ background:var(--neutral-bg); color:var(--text-muted); }
        .c-p0, .c-p1 { background:var(--failed-bg); color:var(--failed); } .c-p2 { background:var(--warning-bg); color:var(--warning); }
        .c-p3 { background:var(--neutral-bg); color:var(--text-muted); }
        .rv-rounds { margin-top:var(--space-14); }
        .rv-round { border:1px solid var(--border-subtle); border-radius:var(--radius-6); background:var(--surface-raised);
                    padding:var(--space-10) var(--space-12); margin:var(--space-8) 0; }
        .rv-round-head { display:flex; gap:var(--space-8); align-items:baseline; margin-bottom:var(--space-4); }
        .rv-round-head .meta { flex:1; min-width:0; white-space:nowrap; overflow:hidden;
                               text-overflow:ellipsis; }
        .rv-foldable { cursor:pointer; user-select:none; }
        .rv-foldable:hover .rv-mark, .rv-foldable:hover strong { color:var(--text-strong); }
        .rv-mark { display:inline-block; width:10px; color:var(--text-faint); font-size:var(--fs-xs); }
        .rv-finding-line { padding:var(--space-2) 0; }
        .rv-phase { padding:var(--space-3) 0; }
        .rv-phase-head { display:flex; gap:var(--space-8); align-items:baseline; }
        .rv-glyph { width:12px; color:var(--accent); text-align:center; }
        .rv-label { color:var(--text); min-width:52px; font-size:var(--fs-sm); }
        .rv-findings { margin:var(--space-4) 0 var(--space-6) var(--space-20); padding-left:var(--space-14); }
        .rv-finding { padding:var(--space-3) 0; }
        .rv-loc { color:var(--text-muted); font-size:var(--fs-xs); margin-left:var(--space-26); }
        .rv-prose { margin:var(--space-3) 0 var(--space-6) var(--space-26); }
        .rv-prose .md { background:none; border:none; padding:0; color:var(--text-muted);
                        font-size:var(--fs-sm); line-height:1.6; }
        .rv-prose .md p { margin:var(--space-3) 0; }
        .pane { padding:var(--space-18) var(--space-24); overflow:auto; }
        /* A rule per section, so the pane reads as areas rather than as one
           scroll. The first carries none — there is nothing above it to be
           separated from. */
        .pane h2, .ws-page h2 { border-top:1px solid var(--border-subtle); padding-top:var(--space-16); }
        .pane h2:first-of-type, .ws-page h2:first-of-type { border-top:none; padding-top:0; }
        /* A workstream's own page: the pane is the content area's only column. */
        .ws-page-crumb { margin:0 0 var(--space-10); font-size:var(--fs-sm); }
        .ws-page .pane-open { display:none; }
        .pane-tag .pane-open { margin-left:var(--space-10); font-family:var(--font-prose); }
        .pos { display:inline-flex; gap:var(--space-6); align-items:baseline; margin-left:var(--space-8); }
        .pos .pos-at { font-size:var(--fs-xs); color:var(--text-muted); }
        .pos.pos-you .pos-at { color:var(--needs-dim); }
        .st-mark { flex:none; font-size:var(--fs-xs); line-height:1; }
        .st-mark.paused { color:var(--dormant); }
        .st-mark.stalled { color:var(--failed); }
        .pause-line { display:flex; align-items:center; gap:var(--space-8); margin:-4px 0 var(--space-10);
                      font-size:var(--fs-xs); color:var(--dormant); }
        .pause-ctl { margin-left:auto; }
        .phase-chip { font-size:var(--fs-xs); color:var(--text-muted); border:1px solid var(--border); border-radius:var(--radius-4); padding:0 var(--space-5); margin-left:var(--space-6); }
        .gate-sub span.phase-chip { flex:none; }
        .plan { margin:var(--space-10) 0 var(--space-14); }
        .plan h3 { font-size:var(--fs-sm); margin:0 0 var(--space-8); color:var(--text); }
        .plan ol { list-style:none; margin:0; padding:0; border:1px solid var(--border); border-radius:var(--radius-6); }
        .plan li { padding:var(--space-8) var(--space-12); border-top:1px solid var(--border-subtle); }
        .plan li:first-child { border-top:none; }
        .plan .ph { display:flex; gap:var(--space-10); align-items:baseline; }
        .plan .mk { width:14px; color:var(--text-faint); }
        .plan li.landed .mk { color:var(--ok-dim); }
        .plan li.current .mk { color:var(--working-dim); }
        .plan li.current .cl { color:var(--text-strong); font-weight:600; }
        .plan li.ahead .cl { color:var(--text-muted); }
        .plan .pm { font-size:var(--fs-xs); color:var(--text-faint); margin:var(--space-3) 0 0 var(--space-24); }
        .plan .pm.owed { color:var(--needs-dim); }
        .plan .pm.pnr { color:var(--failed-dim); }
        .gate-criterion { font-size:var(--fs-xs); color:var(--needs-dim); margin-top:var(--space-3); }
        /* One per row, full text. Three columns fitted the cards on screen and
           made the RECORDS unreadable: a baseline's area or a design's summary is
           several sentences, and a 220px column clamped at four lines showed
           about a third of one. These are the three things the pane exists to
           state, so they are stated. */
        .holds { display:flex; flex-direction:column; gap:var(--space-10); margin:var(--space-12) 0; }
        .hold { border:1px solid var(--border); border-radius:var(--radius-6); background:var(--surface);
                padding:var(--space-10) var(--space-12); min-width:0; }
        .hold .hh { display:flex; gap:var(--space-8); align-items:baseline; margin-bottom:var(--space-5); }
        .hold .hk { font-size:var(--fs-xs); text-transform:uppercase; color:var(--text-muted); }
        .hold .hs { font-size:var(--fs-xs); color:var(--text-faint); }
        /* No clamp, and whitespace COLLAPSED rather than preserved. These
           strings are soft-wrapped in the EDN a human typed, so the newlines and
           the leading indentation are an artifact of the source file, not of the
           prose; pre-wrap renders them as hard breaks with the indent showing. */
        .hold .hb { font-size:var(--fs-sm); line-height:1.55; color:var(--text);
                    overflow-wrap:anywhere; }
        .hold .hf { margin-top:var(--space-6); font-size:var(--fs-xs); }
        .hold .ok { color:var(--ok-dim); }
        .hold .no { color:var(--waiting-dim); }
        .hold.empty { border-style:dashed; background:none; }
        .hold.empty .hb { color:var(--text-faint); }
        /* The arc. A vertical list rather than a horizontal track, for the
           reason the holds are: a stage carries several facts and a lane wide
           enough for eight of them leaves room for none of them. It is also not
           a progress bar — a bar draws a walk that only goes forward, and the
           re-entry count is the one thing here a forward-only shape cannot
           show. */
        .arc { margin:var(--space-12) 0 var(--space-4); border:1px solid var(--border-subtle); border-radius:var(--radius-6); overflow:hidden; }
        .arc-row { display:flex; gap:var(--space-10); align-items:baseline; padding:var(--space-7) var(--space-12);
                   border-bottom:1px solid var(--border-subtle); color:var(--text); }
        .arc-row:last-child { border-bottom:none; }
        .arc-row.live { cursor:pointer; }
        .arc-row.live:hover { background:var(--surface-hover); }
        .arc-row.open { background:var(--surface-hover); }
        .arc-g { width:12px; text-align:center; color:var(--text-faint); font-size:var(--fs-xs); }
        .arc-n { min-width:118px; font-size:var(--fs-sm); color:var(--text-muted); }
        .arc-m { font-size:var(--fs-xs); color:var(--text-faint); }
        .arc-d { font-size:var(--fs-xs); color:var(--working-dim); }
        .arc-v { font-size:var(--fs-xs); color:var(--waiting); }
        .arc-row.done .arc-g { color:var(--ok-dim); }
        .arc-row.done .arc-n { color:var(--text); }
        .arc-row.current .arc-g { color:var(--working-dim); }
        .arc-row.current .arc-n { color:var(--text-strong); font-weight:600; }
        .arc-row.stale .arc-g { color:var(--waiting); }
        .arc-row.stale .arc-n { color:var(--waiting); }
        .arc-row.stale .arc-m { color:var(--waiting-dim); }
        .arc-row.skipped .arc-n { color:var(--waiting-dim); }
        .arc-row.skipped .arc-m { color:var(--waiting-dim); }
        .arc-row.ahead .arc-n { color:var(--text-faint); }
        /* An agent working in a stage: a breathing dot in the working colour in place of its
           glyph — activity, not a load in progress. A stage whose next move is a person's
           carries the needs-you dot instead, which outranks it. */
        .arc-live, .st-mark.live { display:inline-block; width:7px; height:7px; border-radius:50%;
                                   background:var(--working); animation:pulse var(--dur-1400) ease-in-out infinite; }
        @media (prefers-reduced-motion: reduce) { .arc-live, .st-mark.live { animation:none; } }
        .arc-row.awaiting .arc-n { color:var(--needs); }
        .arc-sub { padding:var(--space-2) 0 var(--space-8); background:var(--surface-sunken); border-bottom:1px solid var(--border-subtle); }
        .hist-head { display:inline-flex; gap:var(--space-7); align-items:baseline; cursor:pointer;
                     font-size:var(--fs-sm); color:var(--text-muted); padding:var(--space-3) 0; }
        .hist-head:hover { color:var(--text); }
        .hist-head .hc { color:var(--text-faint); }
        .ledger-index { margin:var(--space-14) 0 var(--space-8); border:1px solid var(--border-subtle); border-radius:var(--radius-6); overflow:hidden; }
        .ledger-row { display:flex; gap:var(--space-10); align-items:baseline; padding:var(--space-7) var(--space-12);
                      border-bottom:1px solid var(--border-subtle); color:var(--text); cursor:pointer; }
        .ledger-row:last-child { border-bottom:none; }
        .ledger-row:hover { background:var(--surface-raised); }
        .ledger-row.sel { background:var(--surface-selected); }
        .ledger-row .lk { font-size:var(--fs-xs); text-transform:uppercase; color:var(--text-muted); min-width:54px; }
        .ledger-row .meta { min-width:78px; }
        .ledger-row .lt { flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:var(--text-strong); }
        .ledger-row.superseded .lt { color:var(--text-faint); text-decoration:line-through; }
        .ledger-row.superseded .lk { color:var(--text-faint); }
        .ledger-row .lx { font-size:var(--fs-xs); color:var(--waiting-dim); border:1px solid var(--waiting-bg); border-radius:var(--radius-4);
                          padding:0 var(--space-6); white-space:nowrap; }
        .viewer { margin:var(--space-12) 0 var(--space-8); border:1px solid var(--border); border-radius:var(--radius-6);
                  background:var(--surface-sunken); overflow:hidden; }
        .viewer-bar { display:flex; gap:var(--space-10); align-items:baseline; padding:var(--space-8) var(--space-12);
                      background:var(--surface-raised); border-bottom:1px solid var(--border); }
        .viewer-bar .lk { font-size:var(--fs-xs); text-transform:uppercase; color:var(--text-muted); }
        .viewer-bar .lt { flex:1; min-width:0; white-space:nowrap; overflow:hidden;
                          text-overflow:ellipsis; color:var(--text-strong); }
        .viewer-close { background:none; border:none; color:var(--text-muted); cursor:pointer;
                        font-size:var(--fs-sm); padding:0 var(--space-2); line-height:1; }
        .viewer-close:hover { color:var(--text-strong); }
        .viewer > .md { border:none; border-radius:0; background:none; }
        .md { font-family:var(--font-prose);
              font-size:var(--fs-sm); line-height:1.7; color:var(--text); background:var(--surface-sunken);
              border:1px solid var(--border); border-radius:var(--radius-6); padding:var(--space-16) var(--space-18); }
        .md code { background:var(--surface-code); padding:var(--space-1) var(--space-5); border-radius:var(--radius-3); color:var(--accent-strong); }
        .md pre { background:var(--surface-code); padding:var(--space-8) var(--space-10); border-radius:var(--radius-4); overflow-x:auto;
                  white-space:pre; font-size:var(--fs-xs); line-height:1.5; }
        .md pre code { background:none; padding:0; color:inherit; }
        /* Prose rendered into a host that is already a card (a ledger card is itself .md) sits
           in the host's box rather than drawing a second one inside it. */
        .md .md { background:none; border:none; border-radius:0; padding:0; }
        .options { display:flex; flex-direction:column; gap:var(--space-8); margin-top:var(--space-6); }
        .option { border:1px solid var(--border); border-radius:var(--radius-6); background:var(--surface-raised);
                  padding:var(--space-10) var(--space-12) var(--space-11); }
        .option-head { display:flex; gap:var(--space-9); align-items:baseline; }
        .option-letter { display:inline-block; min-width:20px; padding:var(--space-1) 0; border-radius:var(--radius-4);
                         background:var(--surface-selected); color:var(--accent); font-size:var(--fs-xs); font-weight:600;
                         text-align:center; }
        .option p { margin:var(--space-5) 0 0; }
        .chip.c-opt-rec { background:var(--recommended-bg); color:var(--recommended); }
        .links { margin:var(--space-6) 0 var(--space-14); }
        .link-row { display:flex; gap:var(--space-10); align-items:baseline; font-size:var(--fs-xs);
                    line-height:1.9; }
        .link-k { color:var(--text-faint); font-size:var(--fs-xs); text-transform:uppercase;
                  letter-spacing:0.04em; min-width:82px; }"
   ;; top bar + sidebar + content-area chrome
   " body { margin:0; padding:0; display:grid; grid-template-columns:180px 1fr;
            grid-template-rows:auto minmax(0, 1fr); grid-template-areas:'top top' 'side main'; height:100vh; }
     .topbar { grid-area:top; display:flex; align-items:center; gap:var(--space-16); padding:var(--space-12) var(--space-22);
               background:var(--surface-raised); border-bottom:1px solid var(--border); }
     .topbar-brand { line-height:0; }
     .topbar-brand:hover { text-decoration:none; }
     .topbar-logo { width:44px; height:44px; border-radius:50%; transition:filter var(--dur-150) ease; }
     .topbar-brand:hover .topbar-logo { filter:brightness(1.12); }
     .topbar-gap { flex:1; }
     .topbar-link { display:flex; align-items:center; gap:var(--space-8); padding:var(--space-7) var(--space-12); border-radius:var(--radius-5);
                    color:var(--text-muted); }
     .topbar-link:hover { background:var(--surface-hover); text-decoration:none; }
     .topbar-link.active { background:var(--surface); color:var(--text-strong); }
     .switcher, .gear { position:relative; }
     .switcher summary, .gear summary { list-style:none; cursor:pointer; padding:var(--space-7) var(--space-12); border-radius:var(--radius-5);
                                        color:var(--text-strong); }
     .switcher summary::-webkit-details-marker, .gear summary::-webkit-details-marker { display:none; }
     .switcher summary:hover, .gear summary:hover { background:var(--surface-hover); }
     .switcher summary { font-size:var(--fs-lg); }
     .gear summary { color:var(--text-muted); font-size:var(--fs-icon); line-height:1; }
     .menu { position:absolute; top:calc(100% + 4px); z-index:60; min-width:160px; padding:var(--space-4);
             background:var(--surface); border:1px solid var(--border); border-radius:var(--radius-6); }
     .switcher .menu { left:0; } .gear .menu { right:0; }
     .menu a { display:block; padding:var(--space-6) var(--space-10); border-radius:var(--radius-4); color:var(--text-muted); }
     .menu a:hover { background:var(--surface-hover); text-decoration:none; }
     .menu a.active { color:var(--text-strong); }
     .sidebar { grid-area:side; background:var(--surface-raised); border-right:1px solid var(--border);
                padding:var(--space-14); display:flex; flex-direction:column; gap:var(--space-3); }
     .side-link { display:block; padding:var(--space-7) var(--space-10); border-radius:var(--radius-5); color:var(--text-muted);
                  border-left:3px solid transparent; }
     .side-link:hover { background:var(--surface-hover); text-decoration:none; }
     .side-divider { border:none; border-top:1px solid var(--border); margin:var(--space-8) 0; width:100%; }
     .side-link.active { background:var(--surface); color:var(--text-strong); border-left-color:var(--accent); }
     .content { grid-area:main; padding:var(--space-20) var(--space-28); overflow:auto; }
     .content.bleed { padding:0; overflow:hidden; display:grid; }
     .structure-frame { width:100%; height:100%; border:0; display:block; }
     .structure-wrap { display:grid; grid-template-rows:auto 1fr; height:100%; min-height:0; }
     .structure-tabs { display:flex; gap:var(--space-18); padding:var(--space-10) var(--space-20); border-bottom:1px solid var(--border); }
     .content:not(.bleed) > .structure-tabs { padding:0 0 var(--space-10); margin:0 0 var(--space-18); }
     .structure-tabs a { color:var(--text-muted); text-decoration:none; padding-bottom:var(--space-4); }
     .structure-tabs a.active { color:var(--text-strong); border-bottom:2px solid var(--text-strong); }
     /* With the chat's side panel, a third column under the top bar: room for its toggle when
        closed, the panel when open — below 1000px it covers the page instead. */
     body:has(> .chat-side) { grid-template-columns:180px 1fr 58px; grid-template-areas:'top top top' 'side main chat';
                              transition:grid-template-columns var(--dur-180) ease-out; }
     body:has(> .chat-side.open) { grid-template-columns:180px 1fr 420px; }
     body > .chat-side { grid-area:chat; height:100%; position:static; }
     @media (max-width: 999px) { body:has(> .chat-side.open) { grid-template-columns:180px 1fr 58px; } }
     @media (prefers-reduced-motion: reduce) { body:has(> .chat-side) { transition:none; } }
     .rail-badge { background:var(--accent-bg); color:var(--accent-strong); border-radius:var(--radius-9);
                   padding:0 var(--space-7); font-size:var(--fs-xs); min-width:18px; text-align:center; }
     .rail-badge.zero { background:var(--neutral-bg); color:var(--text-faint); }
     .rail-health { margin-top:auto; padding-top:var(--space-14); font-size:var(--fs-xs); color:var(--text-muted); }
     .rail-health-btn { background:none; border:none; color:inherit; font:inherit;
                        cursor:pointer; padding:0; display:flex; align-items:center; }
     .ops-wrap { position:fixed; left:14px; bottom:14px; width:340px; max-width:360px;
                 max-height:70vh; overflow-y:auto; z-index:50; }
     .ops-panel .card { background:var(--surface); }
     .ops-panel .fire-row { display:flex; align-items:center; gap:var(--space-6); flex-wrap:wrap;
                             margin:var(--space-6) 0; }
     .ops-panel .fire-row input { background:var(--surface-sunken); border:1px solid var(--border);
                                   border-radius:var(--radius-4); color:var(--text-strong); font:inherit;
                                   font-size:var(--fs-xs); padding:var(--space-4) var(--space-8); width:100px; }
     .ops-panel .fleet-head { display:flex; justify-content:space-between; align-items:baseline; gap:var(--space-8); }
     .ops-panel .fleet-bar { height:4px; border-radius:var(--radius-2); background:var(--border);
                             margin:var(--space-7) 0 var(--space-6); overflow:hidden; }
     .ops-panel .fleet-bar span { display:block; height:100%; background:var(--ok); }
     .ops-panel .fleet-over .fleet-bar span { background:var(--warning); }
     .ops-panel .fleet-over .fleet-pct { color:var(--warning); }
     .ops-panel .fleet-cand { display:flex; justify-content:space-between; gap:var(--space-8);
                              font-size:var(--fs-xs); margin-top:var(--space-3); }
     .ops-panel .fleet-cand .nm { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
     .ops-panel .fleet-cand .sz { color:var(--text-muted); white-space:nowrap; }
     .dot { width:8px; height:8px; border-radius:50%; display:inline-block; margin-right:var(--space-6); }
     .dot-up { background:var(--ok); box-shadow:var(--glow-ok); }
     .dot-halted { background:var(--failed); box-shadow:var(--glow-failed); }
     .dot-breaker { background:var(--warning); }
     .dot-down { background:var(--text-faint); }
     .doing-badge { color:var(--working-dim); }
     .doing-badge::before { content:'● '; }
     .badge-findings { color:var(--warning-dim); border:1px solid var(--warning-bg); border-radius:var(--radius-4); padding:var(--space-1) var(--space-6); font-size:var(--fs-xs); margin-left:var(--space-6); }
     .doing-badge.ship-blocked { color:var(--failed); font-weight:600; }
     .doing-badge.ship-driving { color:var(--working-dim); }
     .doing-badge.ship-awaiting-merge { color:var(--text-faint); }
     .doing-badge.ship-queued { color:var(--text-faint); }
     .ws-section { margin-top:var(--space-2); }
     .ws-fold-header { cursor:pointer; user-select:none; display:flex; align-items:center;
                       gap:var(--space-6); }
     .ws-fold-header:hover { color:var(--text); }
     .ws-fold-mark { display:inline-block; width:10px; color:var(--text-faint); font-size:var(--fs-xs); }
     .ws-fold-count { color:var(--text-faint); font-size:var(--fs-xs); }
     .ws-section-rows { overflow:hidden; max-height:0; opacity:0;
                        transition:max-height var(--dur-280) ease, opacity var(--dur-200) ease; }
     .ws-section-rows.ws-open { max-height:4000px; opacity:1; }
     .winddown { opacity: 0.65; }
        /* Operations — the proposal is the unit, so the row is the design.
           Evidence is what a decision is actually about, so it is on the card
           rather than behind a disclosure; the proposal is what you are being
           asked to accept, so it is set apart from the observation. */
        .prop { border:1px solid var(--border); border-radius:var(--radius-6); padding:var(--space-12) var(--space-14); margin-bottom:var(--space-10);
                background:var(--surface-sunken); }
        /* Only a FINISHED proposal is dimmed. An approval nobody has carried
           out is the most actionable row on the page — dimming it alongside the
           landed ones is what made 'approved' and 'done' look like one state. */
        .prop.settled { opacity:.55; }
        .prop-head { display:flex; gap:var(--space-8); align-items:baseline; flex-wrap:wrap; margin-bottom:var(--space-6); }
        .prop-where { font-family:var(--font-mono); font-size:var(--fs-xs); color:var(--accent-strong); }
        .prop-sum { margin:0 0 var(--space-8); }
        .prop-ev { font-size:var(--fs-xs); color:var(--text-muted); border-left:2px solid var(--border-strong); padding-left:var(--space-10);
                   margin:0 0 var(--space-8); }
        .prop-fix { font-size:var(--fs-sm); border-left:2px solid var(--accent-border); padding-left:var(--space-10); margin:0 0 var(--space-10); }
        .prop-meta { font-size:var(--fs-xs); color:var(--text-faint); display:flex; gap:var(--space-10); flex-wrap:wrap; }
        .prop-verdict { display:inline-block; padding:var(--space-1) var(--space-6); border-radius:var(--radius-3); font-size:var(--fs-xs); }
        .v-approved { background:var(--ok-bg); color:var(--ok); }
        .v-declined { background:var(--failed-bg); color:var(--failed); }
        .prop-landed { background:var(--working-bg); color:var(--working); }
        /* The state the surface had no way to show. Amber rather than grey:
           it is not a quiet footnote, it is the queue of things this board has
           agreed to and not done. */
        .prop-waiting { background:var(--waiting-bg); color:var(--waiting); }
        /* Finished, but by a plan rather than by anything landing. Grey because
           it asks nothing of anyone — unlike the amber above it. */
        .prop-disposed { background:var(--neutral-bg); color:var(--neutral); }
        .prop-note { font-size:var(--fs-xs); color:var(--text-muted); margin:var(--space-8) 0 0; }
        .prop-note b { color:var(--text); font-weight:600; }
        .ops-empty { color:var(--text-faint); padding:var(--space-20) 0; }
        .ops-head { display:flex; gap:var(--space-14); align-items:baseline; margin-bottom:var(--space-14); flex-wrap:wrap; }
        /* One reading column. Not .gate-wrap's queue+pane grid: nothing opens
           beside a proposal, so its 38% column would leave the evidence — the
           part you actually have to read — wrapped against two-thirds empty. */
        .ops-col { max-width:900px; padding-top:var(--space-4); }
        /* The Operations home: a card per concern. The number is the card —
           coloured only when it asks something of someone. */
        .ops-cards { display:grid; grid-template-columns:repeat(auto-fill,minmax(250px,1fr));
                     gap:var(--space-12); margin-top:var(--space-12); }
        .ops-card { display:block; background:var(--surface); border:1px solid var(--border);
                    border-radius:var(--radius-6); padding:var(--space-14) var(--space-16); color:var(--text); }
        .ops-card:hover { border-color:var(--accent-border); text-decoration:none; }
        .ops-card-title { font-size:var(--fs-xs); color:var(--text-muted); text-transform:uppercase;
                          letter-spacing:.06em; }
        .ops-card-num { font-size:var(--fs-lg); margin:var(--space-6) 0 var(--space-4); color:var(--text); }
        .ops-card-num.ops-you { color:var(--needs); }
        .ops-card-num.ops-busy { color:var(--working); }
        .ops-crumb { font-size:var(--fs-xs); color:var(--text-faint); margin-bottom:var(--space-10); }
        /* Review queue — a ticket is a card, its proposed writes rows in it. */
        .rq-item { border-top:1px solid var(--border-subtle); padding:var(--space-8) 0; }
        .rq-item.rq-skipped { opacity:.5; }
        .rq-sum { font-size:var(--fs-sm); }
        .rq-rank { color:var(--warning); min-width:28px; }
        .rq-title { color:var(--text); }
        .rq-why { font-size:var(--fs-xs); color:var(--text-muted); margin:0 0 var(--space-6); }
        .rq-toggle { font-size:var(--fs-xs); color:var(--text-faint); cursor:pointer; }
        .rq-pre { display:none; }
        .rq-pre.rq-shown { display:block; }
        .rq-pre { font-size:var(--fs-xs); color:var(--text); background:var(--surface-sunken);
                  border-left:2px solid var(--accent-border); padding:var(--space-8) var(--space-10); margin:var(--space-6) 0; }
        .rq-order { margin:0 0 var(--space-10); padding-left:var(--space-22); font-size:var(--fs-sm); }
        .rq-flags { font-size:var(--fs-xs); color:var(--warning); }
        .rq-o-applied { background:var(--ok-bg); color:var(--ok); }
        .rq-o-failed { background:var(--failed-bg); color:var(--failed); }
        .rq-o-skipped { background:var(--neutral-bg); color:var(--neutral); }
        .rq-qa-keep { background:var(--ok-bg); color:var(--ok); }
        .rq-qa-write, .rq-qa-correct { background:var(--waiting-bg); color:var(--waiting); }
        /* Session recovery — the state chip is the first thing read, so each
           state has its own colour: red asks for a person, blue is nido working,
           amber is nido waiting, green and grey are finished. */
        .rec-title { font-size:var(--fs-sm); color:var(--text-muted); text-transform:uppercase; letter-spacing:.06em;
                     margin:var(--space-6) 0 var(--space-10); }
        .rec-needs-you { background:var(--needs-bg); color:var(--needs); }
        .rec-recovering { background:var(--working-bg); color:var(--working); }
        .rec-waiting { background:var(--waiting-bg); color:var(--waiting); }
        .rec-owed { background:var(--neutral-bg); color:var(--text); }
        .rec-restored { background:var(--ok-bg); color:var(--ok); }
        .rec-dismissed { background:var(--neutral-bg); color:var(--neutral); }
        /* Red like needs-you: a stuck sweep is released only by a person. */
        .sweep-stuck { background:var(--failed-bg); color:var(--failed); }
        .rec-did { font-size:var(--fs-xs); color:var(--text-muted); margin:0 0 var(--space-8); }
        .rec-feed { font-size:var(--fs-xs); margin-top:var(--space-8); }
        .rec-evt { display:flex; gap:var(--space-10); padding:var(--space-3) 0; border-bottom:1px solid var(--border-subtle); }
        .rec-evt-at { color:var(--text-faint); min-width:64px; }
        .rec-evt-kind { color:var(--accent-strong); min-width:104px; }
        .rec-evt-what { color:var(--text); overflow:hidden; text-overflow:ellipsis; }
        /* The chat panel: a transcript, then the composer. The person's words are set off by a
           tint, the agent's are plain prose; a tool call is a one-line disclosure. */
        .chat { display:flex; flex-direction:column; gap:var(--space-10); min-height:0; font-size:var(--fs-sm); }
        .chat-log { display:flex; flex-direction:column; gap:var(--space-8); overflow-y:auto; min-height:0; flex:1; }
        /* Room under the newest message for its reply to grow into; the log's script sizes it. */
        .chat-log::after { content:''; flex:none; height:var(--chat-tail, 0px); }
        .chat-you { scroll-margin-top:8px; }
        .chat-msg { max-width:var(--measure); }
        .chat-msg p { margin:0 0 var(--space-6); }
        .chat-you { align-self:flex-end; background:var(--accent-bg); border:1px solid var(--accent-border);
                    border-radius:var(--radius-6); padding:var(--space-6) var(--space-10); color:var(--text-strong); }
        .chat-agent { color:var(--text); }
        /* The reply is still being written: a text cursor at its end, blinking like one. */
        .chat-caret { color:var(--working); margin-left:var(--space-2); animation:caret-blink var(--dur-1000) steps(1) infinite; }
        @keyframes caret-blink { 50% { opacity:0; } }
        @media (prefers-reduced-motion: reduce) { .chat-caret { animation:none; } }
        .chat-thought summary, .chat-tool summary { cursor:pointer; color:var(--text-muted);
                    font-size:var(--fs-xs); list-style:none; display:flex; gap:var(--space-6); align-items:baseline; }
        .chat-thought-text { color:var(--text-faint); font-style:italic;
                    padding:var(--space-4) 0 0 var(--space-12); }
        .chat-tool { border:1px solid var(--border-subtle); border-radius:var(--radius-4); background:var(--surface-sunken);
                     padding:var(--space-4) var(--space-8); }
        .chat-tool[open] summary { margin-bottom:var(--space-6); }
        .chat-tool-t { font-family:var(--font-mono); color:var(--text); overflow:hidden; text-overflow:ellipsis;
                       white-space:nowrap; }
        .chat-tool-out, .chat-diff pre { font-family:var(--font-mono); font-size:var(--fs-xs); margin:0;
                       max-height:240px; overflow:auto; background:var(--surface-code);
                       padding:var(--space-6); border-radius:var(--radius-3); }
        .chat-diff { margin-top:var(--space-6); }
        .chat-diff-path { font-family:var(--font-mono); font-size:var(--fs-xs); color:var(--text-muted); }
        .chat-diff-old { color:var(--failed-dim); }
        .chat-diff-new { color:var(--ok-dim); }
        .chat-st { font-size:var(--fs-xs); }
        .chat-st-waiting { color:var(--waiting); }
        .chat-st-running { color:var(--working); }
        .chat-st-ok { color:var(--ok); }
        .chat-st-failed { color:var(--failed); }
        .chat-perm { border:1px solid var(--needs-dim); border-radius:var(--radius-6); background:var(--needs-bg); padding:var(--space-8) var(--space-10); }
        .chat-perm-q { margin:0 0 var(--space-8); color:var(--text-strong); }
        .chat-perm-opts { display:flex; gap:var(--space-8); flex-wrap:wrap; }
        .chat-perm.answered { border-color:var(--border-subtle); background:transparent; display:flex; gap:var(--space-8);
                              align-items:baseline; color:var(--text-muted); font-size:var(--fs-xs); }
        .chat-perm-a { color:var(--text); }
        .chat-err { display:flex; gap:var(--space-8); align-items:baseline; color:var(--failed-strong); background:var(--failed-bg);
                    border-radius:var(--radius-4); padding:var(--space-6) var(--space-10); }
        /* Something done to the worktree from outside a turn: quiet, centred, neither side's voice. */
        .chat-note { margin:0; align-self:center; color:var(--text-muted); font-size:var(--fs-xs); }
        /* The conversation's unlanded change, above the log: what it touches, what it would land as,
           and Publish / Cancel — each confirmed in place rather than by a dialog. */
        .chat-change { display:flex; flex-direction:column; gap:var(--space-6); font-size:var(--fs-xs); }
        .chat-change:not(:empty) { border:1px solid var(--border); border-radius:var(--radius-6); padding:var(--space-8) var(--space-10);
                                   background:var(--surface-sunken); }
        .chat-change p { margin:0; }
        .chat-change-files summary { cursor:pointer; color:var(--text-muted); }
        .chat-change-files ul { margin:var(--space-4) 0 0; padding-left:var(--space-16); max-height:120px; overflow-y:auto; }
        .chat-change-st { color:var(--text-muted); }
        .chat-change-desc { color:var(--text-strong); }
        .chat-change-acts, .chat-change-ask { display:flex; gap:var(--space-6); align-items:center; justify-content:flex-end; }
        .chat-change-ask span { flex:1; color:var(--text); }
        .chat-change-status { display:flex; gap:var(--space-6); align-items:center; color:var(--text); }
        .chat-change-done { color:var(--text-muted); }
        .chat-change-refused { color:var(--failed-strong); }
        .chat-compose { display:flex; flex-direction:column; gap:var(--space-6); }
        /* The send/stop button sits inside the field's bottom-right corner, not on a row of its own. */
        .chat-field { position:relative; }
        .chat-compose textarea { display:block; width:100%; resize:vertical; background:var(--surface-sunken);
                    color:var(--text); border:1px solid var(--border); border-radius:var(--radius-4);
                    padding:var(--space-6) var(--space-72) var(--space-6) var(--space-8); font:inherit; }
        .chat-compose[data-attach-url] textarea { padding-right:var(--space-136); }
        .chat-compose textarea:focus { outline:none; border-color:var(--accent-border); }
        .chat-actions { position:absolute; right:6px; bottom:6px; display:flex; gap:var(--space-8); }
        /* The turn's status row: a breathing mark, the phase in words, the seconds so far. The mark
           is in the working colour whatever the agent is doing — the words tell thinking from
           running from writing — and in the needs colour, still, when it waits on your answer. */
        .chat-status { display:flex; gap:var(--space-8); align-items:center; min-height:20px; font-size:var(--fs-xs);
                       color:var(--text-muted); }
        .chat-status:empty { display:none; }
        /* One line, however long the phase's words run — a tool call's title can be a whole
           script; the rest is cut with an ellipsis and stays readable on hover. */
        .chat-status-l { color:var(--text); flex:1 1 auto; min-width:0; white-space:nowrap;
                         overflow:hidden; text-overflow:ellipsis; }
        .chat-status > .chat-spin, .chat-elapsed { flex:none; }
        .chat-elapsed { color:var(--text-faint); font-variant-numeric:tabular-nums; }
        /* The side panel (see nido.ui.components.chat/panel). Its width is its page's; closed, only
           the toggle at the head of its bar shows. The toggle's two icons are stacked by absolute
           position in a fixed box, which every engine lays out the same. */
        .chat-side { border-left:1px solid var(--border); display:flex; flex-direction:column; background:var(--bg);
                     padding:var(--space-12) var(--space-14); gap:var(--space-10); min-height:0; height:100vh; overflow:hidden; min-width:0;
                     position:sticky; top:0; }
        .chat-side:not(.open) > :not(.chat-side-bar),
        .chat-side:not(.open) .chat-side-bar > :not(.chat-toggle) { display:none; }
        .chat-side > .chat { flex:1; }
        .chat-side-bar { display:flex; gap:var(--space-8); align-items:center; }
        .chat-side-bar h1 { margin:0; font-size:var(--fs-md); flex:1; }
        /* New conversation, and the ask that replaces the heading while it is confirmed. */
        .chat-new { flex:none; font-size:var(--fs-xs); }
        .chat-new-ask { flex:1; display:flex; gap:var(--space-6); align-items:center; font-size:var(--fs-xs); }
        .chat-new-ask span { flex:1; color:var(--text); }
        .chat-toggle { position:relative; flex:none; width:30px; height:30px; padding:0; border-radius:var(--radius-6); cursor:pointer;
                       background:none; border:1px solid transparent; color:var(--text-muted); }
        .chat-toggle:hover, .chat-side.open .chat-toggle { color:var(--text-strong); }
        .chat-toggle:hover { border-color:var(--border); }
        .chat-toggle svg { position:absolute; inset:0; margin:auto; width:18px; height:18px;
                           transition:opacity var(--dur-180) ease-out; }
        .chat-toggle .chat-i-close, .chat-side.open .chat-toggle .chat-i-open { opacity:0; }
        .chat-side.open .chat-toggle .chat-i-close { opacity:1; }
        .chat-toggle .chat-spin { display:none; position:absolute; top:-3px; right:-3px; }
        .chat-side:not(.open) .chat-toggle.busy .chat-spin { display:block; }
        @media (max-width: 999px) {
          .chat-side.open { position:fixed; top:0; right:0; bottom:0; width:min(420px, 100vw); z-index:60;
                            box-shadow:var(--shadow-side); }
        }
        @media (prefers-reduced-motion: reduce) { .chat-toggle svg { transition:none; } }
        /* A side panel in a box of its own, as a catalog scene shows one: a fixed height it fills,
           at its width open or closed, against the right edge as it sits against the page's. */
        .chat-side.boxed { position:static; height:420px; width:420px; margin-left:auto; }
        .chat-side.boxed:not(.open) { width:58px; }
        /* The catalog scenes' own layout, for a scene that sets several calls of one function
           side by side or pairs an input with what it renders as. */
        .scene-rail { display:flex; flex-wrap:wrap; gap:var(--space-18); align-items:flex-start; }
        .scene-rail-i { display:flex; flex-direction:column; gap:var(--space-4); align-items:flex-start; }
        .scene-pair { display:flex; gap:var(--space-14); align-items:baseline; }
        .scene-label { font-family:var(--font-mono); font-size:var(--fs-xs); color:var(--text-faint); }
        .scene-pair .scene-label { min-width:190px; }
        .scene-empty { color:var(--text-faint); font-style:italic; }
        .chat-spin { width:8px; height:8px; border-radius:50%; background:var(--working);
                     animation:pulse var(--dur-1200) ease-in-out infinite; flex:none; }
        .chat-spin-asking { background:var(--needs); animation:none; }
        @media (prefers-reduced-motion: reduce) { .chat-spin { animation:none; } }
        .chat-thought[open] summary { margin-bottom:var(--space-2); }
        /* Picks: numbered chips, the number the page marks the same element with. */
        .chat-picks { display:flex; flex-wrap:wrap; gap:var(--space-4); }
        .chat-picks:empty { display:none; }
        .chat-picks.sent { list-style:none; margin:0 0 var(--space-6); padding:0; }
        .chat-pick { display:inline-flex; gap:var(--space-5); align-items:center; max-width:100%; font-family:var(--font-mono);
                     font-size:var(--fs-xs); color:var(--accent-strong); border:1px solid var(--accent-border);
                     border-radius:var(--radius-3); padding:var(--space-1) var(--space-4) var(--space-1) var(--space-2); background:var(--surface-sunken); }
        .chat-pick-n { min-width:16px; text-align:center; border-radius:var(--radius-2); background:var(--accent-bg);
                       color:var(--accent-strong); font-variant-numeric:tabular-nums; }
        .chat-pick-l { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
        .chat-pick-x { background:none; border:none; color:var(--text-muted); cursor:pointer; font-size:var(--fs-xs);
                       padding:0 var(--space-2); }
        .chat-pick-x:hover { color:var(--text-strong); }
        /* Images: staged ones as removable thumbnails above the text box, sent ones above the
           message's words; a panel being dragged over with a file is outlined. */
        .chat-attachments { display:flex; flex-wrap:wrap; gap:var(--space-6); }
        .chat-attachments:empty { display:none; }
        .chat-att { position:relative; display:inline-block; }
        .chat-att img { display:block; width:56px; height:56px; object-fit:cover; border-radius:var(--radius-4);
                        border:1px solid var(--border); background:var(--surface-sunken); }
        .chat-att-x { position:absolute; top:-6px; right:-6px; width:18px; height:18px; padding:0; border-radius:50%;
                      border:1px solid var(--border); background:var(--surface); color:var(--text-muted);
                      cursor:pointer; font-size:var(--fs-xs); line-height:16px; }
        .chat-att-x:hover { color:var(--text-strong); }
        .chat-attach-err { margin:0; font-size:var(--fs-xs); color:var(--failed-strong); }
        .chat-attach-err:empty { display:none; }
        .chat-imgs { display:flex; flex-wrap:wrap; gap:var(--space-6); margin:0 0 var(--space-6); }
        .chat-img { display:block; max-width:160px; max-height:120px; border-radius:var(--radius-4); border:1px solid var(--border); }
        .chat.chat-drop { outline:2px dashed var(--accent-border); outline-offset:-2px; }
"))
;; ---------------------------------------------------------------------------
;; What the library is handed

(def ^:private tokens
  (delay (edn/read-string (slurp (io/resource "nido/tokens.edn")))))

(defn ^{:malli/schema [:=> [:cat] :map]}
  stylesheet
  "nido's inputs to the library's stylesheet call: `:tokens`, the token map from
   resources/nido/tokens.edn, and `:rules`, the text of every rule no block registration carries."
  []
  {:tokens @tokens :rules app-css})
