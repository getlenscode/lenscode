namespace LensCode.UI
{
    /// <summary>The six agent states from spec §10.</summary>
    public enum AgentState
    {
        Working,   // ●
        Idle,      // ○
        Completed, // ✓
        Attention, // !
        Failed,    // ✕
        Stopped,   // ■
    }

    public static class AgentStateGlyphs
    {
        public static string Glyph(AgentState state) => state switch
        {
            AgentState.Working => "●",
            AgentState.Idle => "○",
            AgentState.Completed => "✓",
            AgentState.Attention => "!",
            AgentState.Failed => "✕",
            AgentState.Stopped => "■",
            _ => "?",
        };

        /// <summary>Hex color matching the HTML mockup's palette for this state.</summary>
        public static string ColorHex(AgentState state) => state switch
        {
            AgentState.Working => "#59e58c",
            AgentState.Idle => "#64748b",
            AgentState.Completed => "#59e58c",
            AgentState.Attention => "#ffd166",
            AgentState.Failed => "#ff7f95",
            AgentState.Stopped => "#99abc0",
            _ => "#99abc0",
        };
    }
}
