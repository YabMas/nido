(ns canvas.claims.strata-seats
  "The claims the seats-and-generations design commits nido to, declared where they live on.

   Each Claim's name is the claim's id, its docstring the statement, and its :about the elements
   it is about — the same three the design record states, which the landing check compares."
  (:require [canvas.vocab.claim :refer [Claim]]
            [canvas.coordinator.lane.conduct :as conduct]
            [canvas.coordinator.lane.drive :as drive]
            [canvas.coordinator.lane.embodied-strata :as embodied-strata]
            [canvas.coordinator.lane.seats :as coordinator-lane-seats]
            [canvas.coordinator.record.generation :as generation]
            [canvas.coordinator.record.seats :as coordinator-record-seats]
            [canvas.coordinator.record.session :as session]
            [canvas.coordinator.record.standing :as standing]
            [canvas.coordinator.record.workstream :as workstream]
            [canvas.coordinator.report :as report]
            [canvas.coordinator.report.model :as model]
            [canvas.review.merge :as merge]
            [canvas.review.passes :as passes]
            [canvas.review.record :as record]
            [canvas.session.briefing :as briefing]
            [canvas.session.lifecycle :as lifecycle]
            [canvas.session.provisioning :as provisioning]
            [canvas.session.test-pg :as test-pg]
            [canvas.tasks.nido-workstream :as nido-workstream]))

(Claim generation-partitions-ledger
  "Every ledger entry other than an intent, a baseline, a baseline's review or a retraction of one of those belongs to exactly one generation, the one opened by the newest :generation entry at or before it — so a :generation entry belongs to the generation it opens — and a ledger with no :generation entry is wholly its first generation. Intents, baselines, their reviews and their retractions belong to none and are read in every generation: a re-cut changes the design, not the status quo it was surveyed against, so a new generation starts from the workstream's intent and baseline, and a baseline retracted in any generation is retracted in all. record-generation is the one partition provider: a reader that reads by generation takes an entry's generation, the current span or the entry that opened it from there and cuts no generation itself."
  {:about [generation/record-generation] :evidence "round"})

(Claim generation-opened-by-person
  "A :generation entry is appended only through the one verb a person runs, carrying their words and the generation-defining element ids they approve. The ledger's open-generation!, the kind's one writer, refuses it while the workstream is closed between phases or unless it cites a :recut of the generation it closes, and the generic appends refuse the kind, as they refuse a phase gate; the ledger observes no execution state. The opening workflow behind the verb refuses while a review run of the workstream is live, naming the run, through a guard the ledger's writer asks of the workstream it reads under its append lock."
  {:about [workstream/record-workstream report/coordinator-report nido-workstream/nido-workstream] :evidence "round"})

(Claim citations-within-generation
  "A record may cite an intent, a baseline, a baseline's review or a retraction of one of those whatever the generation, and otherwise only entries of its own generation, with three crossings allowed: a :generation entry cites the :recut of the generation it closes, a record cites the :generation entry that opened its own generation, and the first design of a generation supersedes the design that governed the generation before it, which the append check takes from record-generation. The append refuses any other citation that crosses a generation boundary."
  {:about [workstream/record-workstream] :evidence "round"})

(Claim inherited-record-names-crossing
  "What a new generation inherits is carried two ways. Its :generation entry carries by value the claims the previous generation saw falsified with what falsified them, the tensions its :recut names as cause and the base revision the conductor chose, and cites only that :recut. Its first design supersedes the design that governed the previous generation, so that design's claims, rejected alternatives and the person's grants carry forward under the ordinary amendment reading, without a new intent or baseline. A new occupant's brief and the readings it is built from carry nothing an earlier occupant wrote or concluded except as a record the :generation entry carries; the conductor, which continues across the opening, is not bound by this in what it says in free messages. Accepted limit: a reading of a design's lineage — one that follows its supersession chain, as the amendment reading does — crosses the boundary through that first supersession, so a new generation's design round is shown earlier generations' designs and the decisions and findings that judged them."
  {:about [report/coordinator-report] :evidence "round"})

(Claim strata-fixed-within-generation
  "Within a generation its generation-defining elements hold. A design marks which of its elements define its generation, and the ledger reads that mark and nothing about how an element is carried. A generation's ids are fixed by one event: in a re-cut generation, the :generation entry that opens it carries them; in a first generation, the first design marking any element to be cleared or approved fixes them, and until then the generation has none. The append refuses a later design of the generation whose generation-defining ids differ from the fixed ones. A design that keeps the ids but moves a generation-defining element's secret — rather than its interface growing by an operation or a data type — is not cleared by the design round, which asks for a :recut instead."
  {:about [workstream/record-workstream generation/record-generation model/report-model] :evidence "round"})

(Claim governing-design-one-reading
  "Which design governs a workstream is one ledger reading over the current generation's span, which record-generation provides — the design a :between-phases close or a gate newer than every design names, else the newest design — and record-status gives that design with its standing. Every reader within this record's bound that asks which design governs a workstream, the ledger's merge check and the landing included, takes it from one of those two and none takes the newest :design entry for it. A goal amendment's reading of a unit's newest design in the span asks which of that unit's designs answers its amended goal, not which design governs, and is not such a reader."
  {:about [standing/record-standing] :evidence "round"})

(Claim readers-read-through-the-span
  "Standing, open findings, a workstream's position, the append checks and a design round given no design to review take the designs, design decisions and findings they choose to read from the current generation's span, which record-generation provides, each declared over that span, and none of them cuts the ledger by generation itself; intents, baselines and their retractions, belonging to no generation, they read whole. The append check that admits a generation's first design takes the design it may supersede from record-generation too. A design round whose span holds no design reviews none and says so. Accepted limit: a reading handed a design by its caller judges the design it is handed, whatever generation the caller took it from, and the merge check also reads a child's ledger and its fork base on the parent."
  {:about [generation/record-generation standing/record-standing drive/lane-pipeline] :evidence "round"})

(Claim seats-derived
  "Which seats a generation has is [seats-of-a-generation]'s. A seat of an element is one of the current generation's fixed generation-defining ids, and its interface is that element's :interface in the governing design; a generation whose ids are not yet fixed has no seat of an element, so a draft design's marks occupy nothing. The seats level reads the fixed ids from record-generation, takes no selection from its caller and names no sort of element itself; no entry or file stores a seat."
  {:about [coordinator-record-seats/record-seats] :evidence "round"})

(Claim tension-state-derived
  "A :tension names the seat raising it, the seat or the conductor it is addressed to, and the element or claim ids at issue; to whom one is addressed, and which addressees are accepted, is [conductor-addressed-by-role]'s. It is open until an :accord from the addressee that reading gives it, or a :withdrawal from its raiser, cites it; whether it is open is derived, never stored. Who wrote a :tension, :accord or :withdrawal is stamped, not stated: they are appended only through the occupancy lane's record!, which takes the writer from the session it runs in — a seat's occupant as its seat, the conductor as the conductor — and the generic appends refuse these kinds."
  {:about [coordinator-record-seats/record-seats report/coordinator-report] :evidence "round"})

(Claim messages-through-conduct
  "A message from a seat's occupant or the conductor to a seat or the conductor reaches its driven addressee only through conduct, tokened and attributed to its sender, so it never holds the recipient. Which messages a seat or the conductor is still owed is what lane-seats' owed derives from the workstream's message log, never stated here — given in the order sent to whichever session holds the seat or conducts at the time, and none refused because another waits; no session record or conversation holds a seat message, so replacing an occupant loses none and a session that stops holds none. The conductor is the session whose record names it the conductor of the workstream; no record names both a seat and the conductor. A prompt with no token still holds a driven session as a person's."
  {:about [conduct/lane-conduct coordinator-lane-seats/lane-seats] :evidence "round"})

(Claim messages-logged-unjudged
  "Every seat message is appended to its workstream's message log under the generation it was sent in, with sender and addressee; the log is never a ledger entry and no judging prompt is shown it."
  {:about [coordinator-lane-seats/lane-seats record/review-record] :evidence "round"})

(Claim occupant-replaced-from-records
  "A seat's occupant is a driven background session on a session record of the workstream naming its seat and the generation it occupies that seat in; occupying, replacing and reading a seat's occupants read only the current generation's records, so a seat whose element survives a re-cut never takes up a retired occupant. Replacing an occupant stops the old Claude session before a new one starts, and the new occupant's first prompt is built from the ensemble reading — its seat, that seat's interface, its tensions and accords, and the generation's inherited record — never from another session's account."
  {:about [coordinator-lane-seats/lane-seats session/record-session] :evidence "round"})

(Claim start-refuses-live-session
  "conduct's start! refuses a session record whose Claude session the substrate's listing shows alive, whoever its facet names as driver, so starting over a released record never leaves an earlier session running unrecorded."
  {:about [conduct/lane-conduct] :evidence "round"})

(Claim new-generation-new-occupants
  "When a generation opens, every seat occupant of the previous generation is retired, including those whose element survives the re-cut; each seat of the new generation that an embodiment carries is occupied by a session that occupied no seat of an earlier generation, as [one-occupant-per-seat] has every current seat occupied. The conductor continues throughout."
  {:about [coordinator-lane-seats/lane-seats] :evidence "round"})

(Claim layers-name-their-stratum
  "A seat's layer has one identity: its seat — under embodied strata, its stratum — with its position among that seat's layers. Its commit, its PR and its :layer-completed entry each name that identity, the entry carrying the seat beside the position and that seat's count as :layer and :of, and a workstream's layer progress counts distinct identities, so two seats' layers at one position are two layers. The occupancy lane's commit and PR hook is a best-effort reader of the commands a seat runs: of a publication it recognises, it refuses one missing its seat or position and one it cannot read, failing open only on whether the session occupies a seat; it promises nothing of a publication it does not recognise, since no reader of command text can decide what arbitrary Bash will run. What every seat's layer is held to rests on what reads structured data: the :layer-completed entry and the review loop. Separately, the review loop refuses any layer whose brief names a stratum the governing design does not name, a layer naming none not refused by it, and when the briefs or the governing design cannot be read the round stops before any reviewer launches."
  {:about [passes/review-layers report/coordinator-report drive/lane-pipeline coordinator-lane-seats/lane-seats] :evidence "round"})

(Claim undeclared-stratum-escalates
  "In a project that declares a design, a design round over a design naming a stratum canvas does not declare escalates it as undeclared before any judge launches, whether or not any claim carries an :about."
  {:about [record/review-record] :evidence "round"})

(Claim unseated-workstreams-unchanged
  "A workstream whose ledger holds no :generation, :recut or :tension entry and whose designs mark no generation-defining element is read, checked and placed as it was before this change, except for its whole seat and conductor ([seats-of-a-generation], [resident-conductor]) and for what this design changes deliberately for every workstream: which design governs is the one record-status reading; in a project that declares a design, a design round escalates an undeclared stratum instead of dropping it; the review loop refuses a layer naming a stratum the governing design does not; and its sessions share one runtime."
  {:about [generation/record-generation standing/record-standing] :evidence "round"})

(Claim recut-proposed-by-anyone
  "A :recut may be proposed by any seat occupant, the conductor, any other session or a person; it is appended through the occupancy lane's record!, which stamps who proposed it, and names the generation-defining ids it proposes. It changes nothing on its own — the generation's ids, its seats and their occupants stand until a person opens a generation from it."
  {:about [workstream/record-workstream report/coordinator-report] :evidence "round"})

(Claim merge-within-generation
  "A merge whose fork base lies before the parent's current generation is refused, naming the generation the base belongs to; a merge within one generation combines by id as it does today."
  {:about [workstream/record-workstream merge/review-merge] :evidence "round"})

(Claim every-seat-its-own-session
  "Every workstream that has seats and that an embodiment carries is carried by them: each seat has exactly one occupant, a session of its own, except while that occupant is being replaced; the conductor — the session whose record names it the conductor — occupies no seat and never stands in for one. A workstream whose seats no embodiment carries is not carried by seats, and the embodiment asked to carry it refuses, naming why."
  {:about [coordinator-lane-seats/lane-seats] :evidence "round"})

(Claim runtime-per-workstream
  "A runtime — one JVM and REPL, one app instance, and one database where the project gives sessions a database of their own — belongs to a workstream: the first of its sessions to come up starts it, and it stops only when none of the workstream's sessions is up. A runtime provides the deduplicated union of the services its members' profiles ask for. A later session whose profile asks for nothing the runtime lacks joins it without provisioning anything; one that asks for a service it lacks restarts the whole runtime under the union of every member's profile and its own, while every member's Claude session and working copy continue — a runtime is never grown, nor rolled back, service by service. A session that no workstream holds when it comes up has a runtime of its own, keyed by its whole project-and-session name, and when a workstream comes to hold it — whether it is up or not — it is moved onto that workstream's runtime at once: its services are restarted under the workstream's key while its Claude session and working copy continue. Session provisioning keys a runtime by the whole key its caller supplies, never by a path leaf, and knows nothing of workstreams; nido supplies the key. The project's own checkout is the one exception: it is always a runtime of its own, keyed by the project's name, and no workstream's session joins it. A restart that fails leaves the runtime down, its members' Claude sessions and working copies continuing, until a member's start brings it up again; nothing retries it on its own."
  {:about [provisioning/session-engine lifecycle/session-lifecycle] :evidence "round"})

(Claim session-own-working-copy
  "Every session of a workstream keeps a working copy of its own, and a runtime backs onto one of two things, by the kind of the project's sessions. In a project whose sessions are jj workspaces, a runtime runs out of a checkout of its own — a jj workspace of the project whose working copy merges the working copy of every member — and no member's worktree backs it: its JVM's classpath, the app's resources and templates and its generated config are that checkout's, so every file a member changes, code or not, reaches the runtime when the checkout is brought up to date, which every member's sync and every member joining or leaving does; a merge that conflicts is refused, naming the members and paths, and the runtime keeps serving the checkout it had. In a project whose sessions are git worktrees or symlinks, a runtime runs out of its starter's worktree, as the seam on those runtimes names; only such a worktree backs a runtime, and only that runtime's other members are told to load their changes. The app instance serves whatever the JVM holds."
  {:about [lifecycle/session-lifecycle] :evidence "round"})

(Claim sessions-told-workstream-runtime
  "Every session of a workstream is told its workstream's runtime — the nREPL, the app and the database — by its briefing and MCP config, with its own working copy as its working directory."
  {:about [briefing/session-launcher] :evidence "round"})

(Claim running-sessions-join-at-landing
  "Landing this moves every session that is up off a runtime keyed by its worktree leaf: one a workstream holds onto its workstream's runtime, joining the one another session of the workstream already brought up there, and one no workstream holds onto a runtime of its own keyed by its whole project-and-session name. Its services are restarted under the new key while its Claude session and working copy continue. After landing, no session runs on a runtime keyed by a worktree leaf. A slash-named session started before this change moves like any other; its session links stay filed under its leaf key, which is a seam, not a runtime."
  {:about [lifecycle/session-lifecycle] :evidence "round"})

(Claim strata-first-embodiment
  "An embodiment is how a seat is named and its occupant briefed; it selects nothing. Embodied strata is the embodiment for a generation whose ids are strata, as lane-seats' default embodiment is for the whole seat: it carries a workstream whose generation-defining ids are exactly its governing design's strata, refusing one that leaves a stratum unmarked or marks anything else and naming each such element; it names each seat by its stratum's id and briefs an occupant to raise a tension first with the strata its stratum rests on or that rest on it. Its refusal reads the governing design's elements, their sorts and which the generation fixes from record-generation and record-standing directly — a bypass of the seats level this design leaves as it is — and nothing below it treats a stratum as a seat."
  {:about [embodied-strata/lane-embodied-strata] :evidence "round"})

(Claim test-db-own-on-shared-runtime
  "A session on a runtime that runs out of a worktree other than its own is handed, in the config files the runtime's services render into its worktree, a test database of its own keyed by the session — never the runtime's or another session's — and what its worktree held before is restored when it leaves the runtime. The runtime's own test database is the one rendered where the runtime runs out of. The destroy verb drops a session's own test database, and the runtime's only once no other session is on the runtime."
  {:about [provisioning/session-engine test-pg/member-context lifecycle/destroy!] :evidence "round"})
