namespace LensCode.UI
{
    /// <summary>
    /// Display-ready agent data for the strip and its detail card (spec §10).
    /// Populated from Herdr agent/pane state in Phase 2; kept as a plain data
    /// object here so the UI layer has no direct Herdr dependency.
    /// </summary>
    public readonly struct AgentSummary
    {
        public readonly string AgentId;
        public readonly string Provider; // "Claude", "Codex", "Hermes", "Pi", ...
        public readonly AgentState State;
        public readonly string Task;
        public readonly string Machine;
        public readonly string Project;
        public readonly string Worktree;
        public readonly string Branch;
        public readonly int ChangedFileCount;
        public readonly bool ApprovalPending;

        public AgentSummary(
            string agentId, string provider, AgentState state, string task = "",
            string machine = "", string project = "", string worktree = "",
            string branch = "", int changedFileCount = 0, bool approvalPending = false)
        {
            AgentId = agentId;
            Provider = provider;
            State = state;
            Task = task;
            Machine = machine;
            Project = project;
            Worktree = worktree;
            Branch = branch;
            ChangedFileCount = changedFileCount;
            ApprovalPending = approvalPending;
        }
    }
}
