(ns canvas.session.casualties
  "Self-spec: `nido.session.casualties` — the sessions that died with the host."
  (:require [fukan.common.vocab.code.kind :refer [Kind]]
            [fukan.common.vocab.code.module :refer [Module]]
            [fukan.common.vocab.code.operation :refer [Operation]]
            [canvas.coordinator.record.state :refer [Path]]
            [canvas.platform.process :as process]
            [canvas.session.state :as sstate :refer [InstanceId]]
            [fukan.common.typing.malli]))

(Kind Casualty
  "One session that died with the host, as its stored entry parses: the boot it died at, its
   project, its worktree and when it was registered — nil when the entry carries no readable time."
  [:map [:boot-id :string] [:instance-id InstanceId] [:project :string] [:worktree Path]
        [:registered-at [:maybe :string]]])

(Kind CasualtyRecord
  "One boot's record as it is persisted: the boot, the registry entries held for it verbatim —
   the only account of each session the prune does not outlive — and the instance ids a revive
   has settled. Read back only through this shape, each entry parsed to a Casualty — a file that
   does not parse is reported as unreadable, never taken as empty."
  [:map [:boot [:map [:id :string] [:began-at :string]]]
        [:casualties [:vector [:map [:instance-id InstanceId] [:entry :map]]]]
        [:settled [:set InstanceId]]])

(Kind Holding
  "What holding the pre-boot entries answered. :held names what the record now holds; :failed says
   it could not be written, so a caller removing pre-boot entries must not; :no-boot says the host
   boot could not be read, so nothing could be classified."
  [:or
   [:map [:outcome [:enum :held]] [:held [:vector Casualty]]]
   [:map [:outcome [:enum :failed]] [:error :string]]
   [:map [:outcome [:enum :no-boot]]]])

(Kind Unsettled
  "The casualties still owed a revive, and the records that could not be read or hold an entry
   that does not parse to a Casualty."
  [:map [:casualties [:vector Casualty]] [:unreadable [:vector Path]]])

(Module session-casualties
  "Which sessions were up when the host went down, kept past the housekeeping that clears them.

   A registry entry registered before the current host boot died with the host, whatever else is
   true of it: nothing registered then can still be running. An entry with no readable
   registration time is taken as registered before it, as the prune already takes it as old. That
   test is the one thing this module decides, and it is what the prune never drew — to the prune a
   boot reads as a crash. The record is kept apart from the per-instance state root, so the reclaim
   that deletes an instance's state cannot delete the account of it.

   What it stores is only what cannot be recomputed: the entries as they were, and which of them a
   revive has settled. Whether a casualty is still unsettled is derived each time it is asked — its
   worktree still exists, no revive settled it, and no registry entry registered after its boot
   names its instance."
  {:child [Casualty CasualtyRecord Holding Unsettled]}
  (Operation pre-boot? "Whether a registry entry was registered before the host boot."
    {:signature [:=> [:catn [:entry :map] [:boot [:maybe :map]]] :boolean]})
  (Operation record!
    "Hold every pre-boot entry among those given in the current boot's record. Idempotent per
     instance and boot."
    {:signature [:=> [:catn [:entries [:sequential :map]]] Holding]
     :delegates [pre-boot? process/host-boot sstate/casualties-dir]})
  (Operation unsettled "Every casualty of every boot that is still owed a revive."
    {:signature [:=> [:catn] Unsettled]
     :delegates [sstate/casualties-dir sstate/read-registry]})
  (Operation settle!
    "Mark casualties settled — brought up or reopened by a revive, or dismissed by a person —
     answering whether the mark was written."
    {:signature [:=> [:catn [:casualties [:sequential Casualty]]] :boolean]
     :delegates [sstate/casualties-dir]}))
