(ns nido.ui.stylesheet
  "The dashboard's stylesheet: the one `:root` token block, and every class rule a block's
   markup names.

   A namespace of its own because TWO chromes emit it — a dashboard page, and the component
   catalog at /_catalog — and a second copy of the token block would be a second place a hue
   is stored, which is what `nido.ui.tokens-test` exists to prevent. Nothing here renders
   markup, and nothing that renders markup declares a class rule.

   The `:root` block is the only place in `nido.ui.*` where a colour or a font size may be
   written as a literal. Every declaration that uses one, here or in an inline style attribute
   in the markup, reads it as `var(--…)`.

   What this holds is the class rules the dashboard's PAGES AND BLOCKS name. The component
   catalog's own chrome rules are the catalog's, next to the chrome they style, because nothing
   in the dashboard names them — a dev surface's layout has no business riding along on every
   page a person works in. What the catalog takes from here is the stylesheet it renders blocks
   IN, which is the whole point of that being one place.")

;; ---------------------------------------------------------------------------
;; The app's stylesheet

(def ^:private app-css
  (str
   ":root {
          /* Type: five sizes, each with a role — never a size between them. */
          --fs-xs: 12px;  /* labels, metadata, chips */
          --fs-sm: 13px;  /* secondary text, dense rows */
          --fs-md: 15px;  /* body prose */
          --fs-lg: 18px;  /* page and pane titles, a question's line, the big number on a card */
          --font-prose: -apple-system, BlinkMacSystemFont, 'Segoe UI', system-ui, sans-serif;
          --font-mono: ui-monospace, SFMono-Regular, Menlo, monospace;
          --measure: 72ch; /* the reading width of a prose block */
          /* What a colour IS: surfaces, borders, controls, text. */
          --bg: #1a1a2e;
          --surface: #16213e;
          --surface-raised: #13132a;
          --surface-sunken: #0f0f1e;
          --surface-hover: #1b1b33;
          --surface-selected: #1a2238;
          --surface-code: #1c1c33;
          --border: #2a2a4a;
          --border-subtle: #20203a;
          --border-strong: #3a3a5a;
          --control-bg: #2a2a4a;
          --control-bg-hover: #3a3a5a;
          --text-strong: #f0f0f8;
          --text: #dcdce8;
          --text-muted: #9a9ac0;
          --text-faint: #75758f;
          /* An action's emphasis: links, the primary button, the selected row. */
          --accent: #7eb8da;
          --accent-strong: #aee0ff;
          --accent-bg: #2a4a6a;
          --accent-border: #3a5a7a;
          /* What a colour MARKS: one hue per state, as a foreground, a dimmer foreground, and a tint. */
          --needs: #facc15;   --needs-dim: #c9a227;   --needs-bg: #2a2a1a;    /* a person owes a move */
          --waiting: #d4b483; --waiting-dim: #8a7a50; --waiting-bg: #3a2e14;  /* nido waits, or a record went stale */
          --working: #7dd3fc; --working-dim: #7a9ad0; --working-bg: #16304a;  /* nido is working */
          --running: #bbf7d0; --running-dim: #6b8f78;                         /* something runs right now */
          --ok: #4ade80;      --ok-dim: #6a9a6a;      --ok-bg: #1a3a2a;       /* done, healthy, approved */
          --failed: #f87171;  --failed-dim: #d9776a;  --failed-bg: #3a1a1a;   /* failed, halted, refused */
          --failed-border: #6a3a3a; --failed-strong: #ffaeae;
          --warning: #fb923c; --warning-dim: #e0a34a; --warning-bg: #3a2a1a;  /* caution: a breaker, a finding */
          --dormant: #c084fc; --dormant-bg: #2a1a2a;                          /* asleep, resumable */
          --neutral: #9aa3ad; --neutral-bg: #22223a;                          /* finished, asks nothing */
          --recommended: #7edaa8; --recommended-bg: #16352a;                  /* the answer nido recommends */
          /* Where a workstream came from. */
          --src-notion: #aee0ff;  --src-notion-bg: #2a3a4a;
          --src-github: #facc15;  --src-github-bg: #2a2a1a;
          --src-slack: #e0a0e0;   --src-slack-bg: #3a2a3a;
          --src-scratch: #9a9ac0; --src-scratch-bg: #252540;
        }
        *, *::before, *::after { box-sizing: border-box; }
        body { font-family: var(--font-prose);
               font-size:var(--fs-md); line-height: 1.6; color: var(--text);
               background: var(--bg); margin: 0; padding: 20px 40px; }
        a { color: var(--accent); text-decoration: none; }
        a:hover { text-decoration: underline; }
        h1 { font-size:var(--fs-lg); color: var(--text-strong); margin: 0 0 8px; }
        h2 { font-size:var(--fs-md); color: var(--text); margin: 24px 0 8px; }
        h3 { font-size:var(--fs-sm); color: var(--text-muted); margin: 16px 0 4px; }
        table { border-collapse: collapse; width: 100%; }
        th, td { padding: 6px 12px; text-align: left; border-bottom: 1px solid var(--border); }
        th { color: var(--text-muted); font-weight: normal; font-size:var(--fs-xs); text-transform: uppercase; }
        .card { background: var(--surface); border: 1px solid var(--border); border-radius: 6px;
                padding: 16px; margin: 8px 0; }
        .meta { color: var(--text-faint); font-size:var(--fs-xs); }
        .empty { color: var(--text-faint); font-style: italic; }
        @keyframes pulse { 0%,100% { opacity: 1; } 50% { opacity: 0.5; } }
        .pulse { animation: pulse 2s ease-in-out infinite; }
        .actions { display: flex; gap: 6px; flex-wrap: wrap; }
        .btn { background: var(--control-bg); color: var(--text); border: 1px solid var(--control-bg-hover);
               padding: 4px 10px; border-radius: 3px; font-size:var(--fs-xs);
               cursor: pointer; font-family: inherit; }
        .btn:hover { background: var(--control-bg-hover); }
        .btn-primary { background: var(--accent-bg); border-color: var(--accent-border); color: var(--accent-strong); }
        .btn-primary:hover { background: var(--accent-border); }
        .btn-danger { background: var(--failed-bg); border-color: var(--failed-border); color: var(--failed-strong); }
        .btn-danger:hover { background: var(--failed-border); }
        .app-state { padding: 2px 8px; border-radius: 3px; font-size:var(--fs-xs);
                     text-transform: uppercase; letter-spacing: 0.04em; }
        .app-state-running { background: var(--ok-bg); color: var(--ok); }
        .app-state-idle { background: var(--waiting-bg); color: var(--waiting); }
        .app-state-starting, .app-state-stopping, .app-state-restarting
          { background: var(--working-bg); color: var(--working);
            animation: pulse 1.5s ease-in-out infinite; }
        .app-state-failed { background: var(--failed-bg); color: var(--failed); }
        .app-state-dormant { background: var(--dormant-bg); color: var(--dormant); }
        .error-msg { color: var(--failed); font-size:var(--fs-xs); margin-top: 4px;
                     max-width: 420px; overflow: hidden; text-overflow: ellipsis;
                     white-space: nowrap; }
        .action-err { color: var(--failed); background: var(--failed-bg); border: 1px solid var(--failed-border);
                      border-radius: 4px; padding: 8px 10px; margin-top: 16px;
                      font-size:var(--fs-xs); }
        .mono, code, pre, kbd { font-family: var(--font-mono); }
        td, .meta { font-variant-numeric: tabular-nums; }
        .badge { display:inline-flex; align-items:center; justify-content:center;
                 width:18px; height:18px; border-radius:4px; font-size:var(--fs-xs); font-weight:bold; }
        .b-notion{ background:var(--src-notion-bg); color:var(--src-notion); } .b-github{ background:var(--src-github-bg); color:var(--src-github); }
        .b-slack{ background:var(--src-slack-bg); color:var(--src-slack); } .b-scratch{ background:var(--src-scratch-bg); color:var(--src-scratch); }
        .gate-wrap { display:grid; grid-template-columns: 38% 62%; min-height:80vh; }
        .queue-col { border-right:1px solid var(--border); display:flex; flex-direction:column;
                     min-width:0; min-height:0; }
        .pickup { margin:12px 16px 10px; padding:12px 14px; border:1px solid var(--border);
                  border-radius:6px; background:var(--surface); }
        .pickup-label { color:var(--text-strong); font-size:var(--fs-xs); text-transform:uppercase; letter-spacing:0.04em; margin-bottom:6px; }
        .pickup-row { display:flex; gap:6px; }
        .pickup input { flex:1; box-sizing:border-box; background:var(--surface-sunken); border:1px solid var(--border);
                        border-radius:4px; color:var(--text-strong); font:inherit; font-size:var(--fs-xs); padding:6px 9px; }
        .pickup input::placeholder { color:var(--text-muted); }
        .pickup input:focus { outline:none; border-color:var(--accent-border); }
        .pickup .btn { color:var(--text-strong); }
        .pickup-result { margin-top:2px; }
        .inbox { overflow:auto; flex:1; }
        .gate-card { display:block; padding:10px 16px; border-bottom:1px solid var(--border-subtle); color:inherit; }
        .gate-card:hover { background:var(--surface-hover); text-decoration:none; }
        .gate-top { display:flex; align-items:center; gap:8px; }
        .gate-top .lbl { flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:var(--text-strong); }
        .needs { width:7px; height:7px; border-radius:50%; background:var(--needs); box-shadow:0 0 6px var(--needs); }
        .gate-tag { color:var(--text-muted); font-family:var(--font-mono); font-size:var(--fs-xs); margin:2px 0 0 26px;
                    white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .pane-tag { color:var(--text-muted); font-family:var(--font-mono); font-size:var(--fs-xs); margin:-6px 0 8px; user-select:all; }
        .gate-sub { display:flex; align-items:center; gap:8px; color:var(--text-faint); font-size:var(--fs-xs); margin:3px 0 0 26px; }
        .gate-sub span:last-child { flex:1; min-width:0; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .gate-prev { color:var(--text-faint); font-size:var(--fs-xs); margin:5px 0 0 26px;
                     white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
        .gate-err { color:var(--failed); font-size:var(--fs-xs); margin:4px 0 0 26px; }
        .qcard { border:1px solid var(--border); border-radius:6px; background:var(--surface-raised);
                 padding:14px 16px; margin:0 0 20px; }
        .qc-head { margin:0 0 10px; color:var(--text-muted); font-size:var(--fs-sm); max-width:var(--measure); }
        .qc-nav { display:flex; align-items:center; gap:6px; margin:0 0 10px; font-size:var(--fs-xs);
                  color:var(--text-faint); text-transform:uppercase; letter-spacing:0.04em; }
        .qc-mark { background:none; border:0; padding:0 2px; cursor:pointer; color:var(--text-faint);
                   font-size:var(--fs-sm); line-height:1; }
        .qc-mark.answered { color:var(--ok); }
        .qc-mark.current { color:var(--text-strong); text-decoration:underline; }
        .qc-q { font-size:var(--fs-lg); font-weight:500; color:var(--text-strong); margin:0 0 10px;
                line-height:1.4; max-width:var(--measure); }
        .qc-ctx p, .qc-rec, .qc-after { font-size:var(--fs-md); color:var(--text); margin:0 0 8px;
                                       max-width:var(--measure); }
        .qc-k { font-weight:600; color:var(--text-muted); margin-right:6px; }
        .qc-opts { display:grid; gap:8px; margin:12px 0 6px; }
        .qc-opt { display:block; width:100%; max-width:var(--measure); text-align:left; font:inherit;
                  color:var(--text); background:var(--surface); border:1px solid var(--border);
                  border-radius:6px; padding:9px 12px; cursor:pointer; }
        .qc-opt:hover { background:var(--surface-hover); }
        .qc-opt.primary { background:var(--accent-bg); border-color:var(--accent-border); }
        .qc-opt.chosen { background:var(--recommended-bg); border-color:var(--recommended); }
        .qc-opt.static { cursor:default; }
        .qc-opt-label { display:block; color:var(--text-strong); }
        .qc-opt-sum { display:block; }
        .qc-opt-cons { display:block; color:var(--text-muted); font-size:var(--fs-sm); }
        .qc-chosen { color:var(--recommended); font-size:var(--fs-xs); margin-left:6px; }
        .qc-step { display:flex; gap:8px; margin:10px 0 0; }
        .ask-note { color:var(--text-muted); font-style:italic; max-width:var(--measure); }
        .controls { display:grid; gap:8px; margin:6px 0 20px; }
        .ans-row { display:grid; grid-template-columns:minmax(150px, max-content) 1fr; gap:12px;
                   align-items:start; padding:10px 12px; border:1px solid var(--border-subtle);
                   border-radius:6px; background:var(--surface-raised); }
        .ans-row.rec { border-color:var(--recommended-bg); }
        .ans-row .btn { font-size:var(--fs-sm); padding:6px 12px; text-align:left; }
        .ans-body p { margin:0 0 4px; max-width:var(--measure); }
        .ans-body .chip { margin-left:0; }
        .ans-cons { color:var(--text-muted); font-size:var(--fs-sm); }
        .ans-letter { font-weight:600; color:var(--text-muted); }
        .ans-does { margin:0; color:var(--text-faint); font-size:var(--fs-sm); max-width:var(--measure); }
        .controls .reply { margin-top:0; }
        .md h1 { font-size:var(--fs-lg); } .md h2 { font-size:var(--fs-md); } .md h3, .md h4 { font-size:var(--fs-sm); }
        .chip { padding:0 7px; border-radius:3px; font-size:var(--fs-xs); text-transform:uppercase; }
        .c-triage{ background:var(--needs-bg); color:var(--needs); } .c-ready{ background:var(--ok-bg); color:var(--ok); }
        .c-in-progress{ background:var(--working-bg); color:var(--working); }
        .c-conf-high{ background:var(--ok-bg); color:var(--ok); } .c-conf-medium{ background:var(--warning-bg); color:var(--warning); }
        .c-conf-low{ background:var(--failed-bg); color:var(--failed); }
        .c-det-bug{ background:var(--warning-bg); color:var(--warning); } .c-det-not-a-bug{ background:var(--neutral-bg); color:var(--text-muted); }
        .c-det-needs-info{ background:var(--working-bg); color:var(--working); }
        .report-meta { margin:0 0 10px; display:flex; gap:6px; flex-wrap:wrap; }
        details.trail { margin-top:14px; } details.trail summary { cursor:pointer; color:var(--text-muted); font-size:var(--fs-xs); }
        .c-rv-ok{ background:var(--ok-bg); color:var(--ok); } .c-rv-warn{ background:var(--warning-bg); color:var(--warning); }
        .c-rv-bad{ background:var(--failed-bg); color:var(--failed); } .c-rv-run{ background:var(--working-bg); color:var(--working); }
        .c-rv-neutral{ background:var(--neutral-bg); color:var(--text-muted); }
        .c-p0, .c-p1 { background:var(--failed-bg); color:var(--failed); } .c-p2 { background:var(--warning-bg); color:var(--warning); }
        .c-p3 { background:var(--neutral-bg); color:var(--text-muted); }
        .rv-rounds { margin-top:14px; }
        .rv-round { border:1px solid var(--border-subtle); border-radius:6px; background:var(--surface-raised);
                    padding:10px 12px; margin:8px 0; }
        .rv-round-head { display:flex; gap:8px; align-items:baseline; margin-bottom:4px; }
        .rv-round-head .meta { flex:1; min-width:0; white-space:nowrap; overflow:hidden;
                               text-overflow:ellipsis; }
        .rv-foldable { cursor:pointer; user-select:none; }
        .rv-foldable:hover .rv-mark, .rv-foldable:hover strong { color:var(--text-strong); }
        .rv-mark { display:inline-block; width:10px; color:var(--text-faint); font-size:var(--fs-xs); }
        .rv-finding-line { padding:2px 0; }
        .rv-phase { padding:3px 0; }
        .rv-phase-head { display:flex; gap:8px; align-items:baseline; }
        .rv-glyph { width:12px; color:var(--accent); text-align:center; }
        .rv-label { color:var(--text); min-width:52px; font-size:var(--fs-sm); }
        .rv-findings { margin:4px 0 6px 20px; padding-left:14px; }
        .rv-finding { padding:3px 0; }
        .rv-loc { color:var(--text-muted); font-size:var(--fs-xs); margin-left:26px; }
        .rv-prose { margin:3px 0 6px 26px; }
        .rv-prose .md { background:none; border:none; padding:0; color:var(--text-muted);
                        font-size:var(--fs-sm); line-height:1.6; }
        .rv-prose .md p { margin:3px 0; }
        .pane { padding:18px 24px; overflow:auto; }
        /* A rule per section, so the pane reads as areas rather than as one
           scroll. The first carries none — there is nothing above it to be
           separated from. */
        .pane h2 { border-top:1px solid var(--border-subtle); padding-top:16px; }
        .pane h2:first-of-type { border-top:none; padding-top:0; }
        .pos { display:inline-flex; gap:6px; align-items:baseline; margin-left:8px; }
        .pos .pos-at { font-size:var(--fs-xs); color:var(--text-muted); }
        .pos.pos-you .pos-at { color:var(--needs-dim); }
        .st-mark { flex:none; font-size:var(--fs-xs); line-height:1; }
        .st-mark.live { color:var(--running); }
        .st-mark.paused { color:var(--dormant); }
        .st-mark.stalled { color:var(--failed); }
        .pause-line { display:flex; align-items:center; gap:8px; margin:-4px 0 10px;
                      font-size:var(--fs-xs); color:var(--dormant); }
        .pause-ctl { margin-left:auto; }
        .phase-chip { font-size:var(--fs-xs); color:var(--text-muted); border:1px solid var(--border); border-radius:4px; padding:0 5px; margin-left:6px; }
        .gate-sub span.phase-chip { flex:none; }
        .plan { margin:10px 0 14px; }
        .plan h3 { font-size:var(--fs-sm); margin:0 0 8px; color:var(--text); }
        .plan ol { list-style:none; margin:0; padding:0; border:1px solid var(--border); border-radius:6px; }
        .plan li { padding:8px 12px; border-top:1px solid var(--border-subtle); }
        .plan li:first-child { border-top:none; }
        .plan .ph { display:flex; gap:10px; align-items:baseline; }
        .plan .mk { width:14px; color:var(--text-faint); }
        .plan li.landed .mk { color:var(--ok-dim); }
        .plan li.current .mk { color:var(--working-dim); }
        .plan li.current .cl { color:var(--text-strong); font-weight:600; }
        .plan li.ahead .cl { color:var(--text-muted); }
        .plan .pm { font-size:var(--fs-xs); color:var(--text-faint); margin:3px 0 0 24px; }
        .plan .pm.owed { color:var(--needs-dim); }
        .plan .pm.pnr { color:var(--failed-dim); }
        .gate-criterion { font-size:var(--fs-xs); color:var(--needs-dim); margin-top:3px; }
        /* One per row, full text. Three columns fitted the cards on screen and
           made the RECORDS unreadable: a baseline's area or a design's summary is
           several sentences, and a 220px column clamped at four lines showed
           about a third of one. These are the three things the pane exists to
           state, so they are stated. */
        .holds { display:flex; flex-direction:column; gap:10px; margin:12px 0; }
        .hold { border:1px solid var(--border); border-radius:6px; background:var(--surface);
                padding:10px 12px; min-width:0; }
        .hold .hh { display:flex; gap:8px; align-items:baseline; margin-bottom:5px; }
        .hold .hk { font-size:var(--fs-xs); text-transform:uppercase; color:var(--text-muted); }
        .hold .hs { font-size:var(--fs-xs); color:var(--text-faint); }
        /* No clamp, and whitespace COLLAPSED rather than preserved. These
           strings are soft-wrapped in the EDN a human typed, so the newlines and
           the leading indentation are an artifact of the source file, not of the
           prose; pre-wrap renders them as hard breaks with the indent showing. */
        .hold .hb { font-size:var(--fs-sm); line-height:1.55; color:var(--text);
                    overflow-wrap:anywhere; }
        .hold .hf { margin-top:6px; font-size:var(--fs-xs); }
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
        .arc { margin:12px 0 4px; border:1px solid var(--border-subtle); border-radius:6px; overflow:hidden; }
        .arc-row { display:flex; gap:10px; align-items:baseline; padding:7px 12px;
                   border-bottom:1px solid var(--border-subtle); color:var(--text); }
        .arc-row:last-child { border-bottom:none; }
        .arc-row.live { cursor:pointer; }
        .arc-row.live:hover { background:var(--surface-hover); }
        .arc-row.open { background:var(--surface-hover); }
        .arc-g { width:12px; text-align:center; color:var(--text-faint); font-size:var(--fs-xs); }
        .arc-n { min-width:118px; font-size:var(--fs-sm); color:var(--text-muted); }
        .arc-m { font-size:var(--fs-xs); color:var(--text-faint); }
        .arc-d { font-size:var(--fs-xs); color:var(--running-dim); }
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
        /* Something running in a stage: a spinner in place of its glyph. A stage
           whose next move is a person's has a mark of its own in the needs
           colour instead, which outranks the spinner. */
        .arc-spin { display:inline-block; width:8px; height:8px; border:1.5px solid var(--running);
                    border-right-color:transparent; border-radius:50%;
                    animation:arc-spin .8s linear infinite; }
        @keyframes arc-spin { to { transform:rotate(360deg); } }
        .arc-wait { color:var(--needs); font-size:var(--fs-sm); line-height:1; }
        .arc-row.awaiting .arc-n { color:var(--needs); }
        .arc-sub { padding:2px 0 8px; background:var(--surface-sunken); border-bottom:1px solid var(--border-subtle); }
        .hist-head { display:inline-flex; gap:7px; align-items:baseline; cursor:pointer;
                     font-size:var(--fs-sm); color:var(--text-muted); padding:3px 0; }
        .hist-head:hover { color:var(--text); }
        .hist-head .hc { color:var(--text-faint); }
        .ledger-index { margin:14px 0 8px; border:1px solid var(--border-subtle); border-radius:6px; overflow:hidden; }
        .ledger-row { display:flex; gap:10px; align-items:baseline; padding:7px 12px;
                      border-bottom:1px solid var(--border-subtle); color:var(--text); cursor:pointer; }
        .ledger-row:last-child { border-bottom:none; }
        .ledger-row:hover { background:var(--surface-raised); }
        .ledger-row.sel { background:var(--surface-selected); }
        .ledger-row .lk { font-size:var(--fs-xs); text-transform:uppercase; color:var(--text-muted); min-width:54px; }
        .ledger-row .meta { min-width:78px; }
        .ledger-row .lt { flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; color:var(--text-strong); }
        .ledger-row.superseded .lt { color:var(--text-faint); text-decoration:line-through; }
        .ledger-row.superseded .lk { color:var(--text-faint); }
        .ledger-row .lx { font-size:var(--fs-xs); color:var(--waiting-dim); border:1px solid var(--waiting-bg); border-radius:4px;
                          padding:0 6px; white-space:nowrap; }
        .viewer { margin:12px 0 8px; border:1px solid var(--border); border-radius:6px;
                  background:var(--surface-sunken); overflow:hidden; }
        .viewer-bar { display:flex; gap:10px; align-items:baseline; padding:8px 12px;
                      background:var(--surface-raised); border-bottom:1px solid var(--border); }
        .viewer-bar .lk { font-size:var(--fs-xs); text-transform:uppercase; color:var(--text-muted); }
        .viewer-bar .lt { flex:1; min-width:0; white-space:nowrap; overflow:hidden;
                          text-overflow:ellipsis; color:var(--text-strong); }
        .viewer-close { background:none; border:none; color:var(--text-muted); cursor:pointer;
                        font-size:var(--fs-sm); padding:0 2px; line-height:1; }
        .viewer-close:hover { color:var(--text-strong); }
        .viewer > .md { border:none; border-radius:0; background:none; }
        .md { font-family:var(--font-prose);
              font-size:var(--fs-sm); line-height:1.7; color:var(--text); background:var(--surface-sunken);
              border:1px solid var(--border); border-radius:6px; padding:16px 18px; }
        .md code { background:var(--surface-code); padding:1px 5px; border-radius:3px; color:var(--accent-strong); }
        .options { display:flex; flex-direction:column; gap:8px; margin-top:6px; }
        .option { border:1px solid var(--border); border-radius:6px; background:var(--surface-raised);
                  padding:10px 12px 11px; }
        .option-head { display:flex; gap:9px; align-items:baseline; }
        .option-letter { display:inline-block; min-width:20px; padding:1px 0; border-radius:4px;
                         background:var(--surface-selected); color:var(--accent); font-size:var(--fs-xs); font-weight:600;
                         text-align:center; }
        .option p { margin:5px 0 0; }
        .chip.c-opt-rec { background:var(--recommended-bg); color:var(--recommended); }
        .reply { margin-top:16px; border:1px solid var(--border); border-radius:6px; background:var(--surface-raised); padding:12px 14px; }
        .reply textarea { width:100%; min-height:62px; background:var(--surface-sunken); border:1px solid var(--border);
                          border-radius:4px; color:var(--text); font:inherit; font-size:var(--fs-sm); padding:9px 11px; }
        .links { margin:6px 0 14px; }
        .link-row { display:flex; gap:10px; align-items:baseline; font-size:var(--fs-xs);
                    line-height:1.9; }
        .link-k { color:var(--text-faint); font-size:var(--fs-xs); text-transform:uppercase;
                  letter-spacing:0.04em; min-width:82px; }"
   ;; rail + content-area chrome
   " body { margin:0; padding:0; display:grid; grid-template-columns:180px 1fr; min-height:100vh; }
     .content { padding:20px 28px; overflow:auto; }
     .rail { background:var(--surface-raised); border-right:1px solid var(--border); padding:18px 14px;
             display:flex; flex-direction:column; gap:3px; }
     .rail-brand { display:block; margin-bottom:16px; line-height:0; }
     .rail-brand:hover { text-decoration:none; }
     .rail-logo { display:block; width:84px; height:84px; border-radius:50%;
                  margin:0 auto; transition:filter .15s ease; }
     .rail-brand:hover .rail-logo { filter:brightness(1.12); }
     .rail-link { display:flex; align-items:center; gap:8px; padding:7px 10px; border-radius:5px;
                  color:var(--text-muted); border-left:3px solid transparent; }
     .rail-link:hover { background:var(--surface-hover); text-decoration:none; }
     .rail-link.active { background:var(--surface); color:var(--text-strong); border-left-color:var(--accent); }
     .rail-badge { margin-left:auto; background:var(--accent-bg); color:var(--accent-strong); border-radius:9px;
                   padding:0 7px; font-size:var(--fs-xs); min-width:18px; text-align:center; }
     .rail-badge.zero { background:var(--neutral-bg); color:var(--text-faint); }
     .rail-scope { margin-top:16px; padding-top:14px; border-top:1px solid var(--border); font-size:var(--fs-xs); }
     .rail-scope a { display:block; padding:3px 6px; color:var(--text-muted); }
     .rail-scope a.active { color:var(--text-strong); }
     .rail-health { margin-top:auto; padding-top:14px; font-size:var(--fs-xs); color:var(--text-muted); }
     .rail-health-btn { background:none; border:none; color:inherit; font:inherit;
                        cursor:pointer; padding:0; display:flex; align-items:center; }
     .ops-wrap { position:fixed; left:14px; bottom:14px; width:340px; max-width:360px;
                 max-height:70vh; overflow-y:auto; z-index:50; }
     .ops-panel .card { background:var(--surface); }
     .ops-panel .fire-row { display:flex; align-items:center; gap:6px; flex-wrap:wrap;
                             margin:6px 0; }
     .ops-panel .fire-row input { background:var(--surface-sunken); border:1px solid var(--border);
                                   border-radius:4px; color:var(--text-strong); font:inherit;
                                   font-size:var(--fs-xs); padding:4px 8px; width:100px; }
     .ops-panel .fleet-head { display:flex; justify-content:space-between; align-items:baseline; gap:8px; }
     .ops-panel .fleet-bar { height:4px; border-radius:2px; background:var(--border);
                             margin:7px 0 6px; overflow:hidden; }
     .ops-panel .fleet-bar span { display:block; height:100%; background:var(--ok); }
     .ops-panel .fleet-over .fleet-bar span { background:var(--warning); }
     .ops-panel .fleet-over .fleet-pct { color:var(--warning); }
     .ops-panel .fleet-cand { display:flex; justify-content:space-between; gap:8px;
                              font-size:var(--fs-xs); margin-top:3px; }
     .ops-panel .fleet-cand .nm { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
     .ops-panel .fleet-cand .sz { color:var(--text-muted); white-space:nowrap; }
     .dot { width:8px; height:8px; border-radius:50%; display:inline-block; margin-right:6px; }
     .dot-up { background:var(--ok); box-shadow:0 0 6px var(--ok); }
     .dot-halted { background:var(--failed); box-shadow:0 0 6px var(--failed); }
     .dot-breaker { background:var(--warning); }
     .dot-down { background:var(--text-faint); }
     .doing-badge { color:var(--running-dim); }
     .doing-badge::before { content:'● '; }
     .badge-findings { color:var(--warning-dim); border:1px solid var(--warning-bg); border-radius:4px; padding:1px 6px; font-size:var(--fs-xs); margin-left:6px; }
     .doing-badge.ship-blocked { color:var(--failed); font-weight:600; }
     .doing-badge.ship-driving { color:var(--working-dim); }
     .doing-badge.ship-awaiting-merge { color:var(--text-faint); }
     .doing-badge.ship-queued { color:var(--text-faint); }
     .ws-section { margin-top:2px; }
     .ws-fold-header { cursor:pointer; user-select:none; display:flex; align-items:center;
                       gap:6px; }
     .ws-fold-header:hover { color:var(--text); }
     .ws-fold-mark { display:inline-block; width:10px; color:var(--text-faint); font-size:var(--fs-xs); }
     .ws-fold-count { color:var(--text-faint); font-size:var(--fs-xs); }
     .ws-section-rows { overflow:hidden; max-height:0; opacity:0;
                        transition:max-height .28s ease, opacity .2s ease; }
     .ws-section-rows.ws-open { max-height:4000px; opacity:1; }
     .winddown { opacity: 0.65; }
        /* Operations — the proposal is the unit, so the row is the design.
           Evidence is what a decision is actually about, so it is on the card
           rather than behind a disclosure; the proposal is what you are being
           asked to accept, so it is set apart from the observation. */
        .prop { border:1px solid var(--border); border-radius:6px; padding:12px 14px; margin-bottom:10px;
                background:var(--surface-sunken); }
        /* Only a FINISHED proposal is dimmed. An approval nobody has carried
           out is the most actionable row on the page — dimming it alongside the
           landed ones is what made 'approved' and 'done' look like one state. */
        .prop.settled { opacity:.55; }
        .prop-head { display:flex; gap:8px; align-items:baseline; flex-wrap:wrap; margin-bottom:6px; }
        .prop-where { font-family:var(--font-mono); font-size:var(--fs-xs); color:var(--accent-strong); }
        .prop-sum { margin:0 0 8px; }
        .prop-ev { font-size:var(--fs-xs); color:var(--text-muted); border-left:2px solid var(--border-strong); padding-left:10px;
                   margin:0 0 8px; }
        .prop-fix { font-size:var(--fs-sm); border-left:2px solid var(--accent-border); padding-left:10px; margin:0 0 10px; }
        .prop-meta { font-size:var(--fs-xs); color:var(--text-faint); display:flex; gap:10px; flex-wrap:wrap; }
        .prop-verdict { display:inline-block; padding:1px 6px; border-radius:3px; font-size:var(--fs-xs); }
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
        .prop-note { font-size:var(--fs-xs); color:var(--text-muted); margin:8px 0 0; }
        .prop-note b { color:var(--text); font-weight:600; }
        .ops-empty { color:var(--text-faint); padding:20px 0; }
        .ops-head { display:flex; gap:14px; align-items:baseline; margin-bottom:14px; flex-wrap:wrap; }
        /* One reading column. Not .gate-wrap's queue+pane grid: nothing opens
           beside a proposal, so its 38% column would leave the evidence — the
           part you actually have to read — wrapped against two-thirds empty. */
        .ops-col { max-width:900px; padding-top:4px; }
        /* The Operations home: a card per concern. The number is the card —
           coloured only when it asks something of someone. */
        .ops-cards { display:grid; grid-template-columns:repeat(auto-fill,minmax(250px,1fr));
                     gap:12px; margin-top:12px; }
        .ops-card { display:block; background:var(--surface); border:1px solid var(--border);
                    border-radius:6px; padding:14px 16px; color:var(--text); }
        .ops-card:hover { border-color:var(--accent-border); text-decoration:none; }
        .ops-card-title { font-size:var(--fs-xs); color:var(--text-muted); text-transform:uppercase;
                          letter-spacing:.06em; }
        .ops-card-num { font-size:var(--fs-lg); margin:6px 0 4px; color:var(--text); }
        .ops-card-num.ops-you { color:var(--needs); }
        .ops-card-num.ops-busy { color:var(--working); }
        .ops-crumb { font-size:var(--fs-xs); color:var(--text-faint); margin-bottom:10px; }
        /* Review queue — a ticket is a card, its proposed writes rows in it. */
        .rq-item { border-top:1px solid var(--border-subtle); padding:8px 0; }
        .rq-item.rq-skipped { opacity:.5; }
        .rq-sum { font-size:var(--fs-sm); }
        .rq-rank { color:var(--warning); min-width:28px; }
        .rq-title { color:var(--text); }
        .rq-why { font-size:var(--fs-xs); color:var(--text-muted); margin:0 0 6px; }
        .rq-toggle { font-size:var(--fs-xs); color:var(--text-faint); cursor:pointer; }
        .rq-pre { display:none; }
        .rq-pre.rq-shown { display:block; }
        .rq-pre { font-size:var(--fs-xs); color:var(--text); background:var(--surface-sunken);
                  border-left:2px solid var(--accent-border); padding:8px 10px; margin:6px 0; }
        .rq-order { margin:0 0 10px; padding-left:22px; font-size:var(--fs-sm); }
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
                     margin:6px 0 10px; }
        .rec-needs-you { background:var(--needs-bg); color:var(--needs); }
        .rec-recovering { background:var(--working-bg); color:var(--working); }
        .rec-waiting { background:var(--waiting-bg); color:var(--waiting); }
        .rec-owed { background:var(--neutral-bg); color:var(--text); }
        .rec-restored { background:var(--ok-bg); color:var(--ok); }
        .rec-dismissed { background:var(--neutral-bg); color:var(--neutral); }
        /* Red like needs-you: a stuck sweep is released only by a person. */
        .sweep-stuck { background:var(--failed-bg); color:var(--failed); }
        .rec-did { font-size:var(--fs-xs); color:var(--text-muted); margin:0 0 8px; }
        .rec-feed { font-size:var(--fs-xs); margin-top:8px; }
        .rec-evt { display:flex; gap:10px; padding:3px 0; border-bottom:1px solid var(--border-subtle); }
        .rec-evt-at { color:var(--text-faint); min-width:64px; }
        .rec-evt-kind { color:var(--accent-strong); min-width:104px; }
        .rec-evt-what { color:var(--text); overflow:hidden; text-overflow:ellipsis; }
"))
;; ---------------------------------------------------------------------------
;; What a chrome asks for

(defn ^{:malli/schema [:=> [:cat] :string]}
  stylesheet
  "The stylesheet every dashboard page emits, as the text of one `<style>` element."
  []
  app-css)

