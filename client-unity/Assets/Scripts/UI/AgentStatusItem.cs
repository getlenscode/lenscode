using Aura.Interaction;
using TMPro;
using UnityEngine;

namespace Aura.UI
{
    /// <summary>
    /// One entry in the persistent agent strip (spec §10): "Claude ● Working".
    /// Selectable via pinch to reveal the detail card.
    /// </summary>
    [RequireComponent(typeof(PinchGesture))]
    public sealed class AgentStatusItem : MonoBehaviour
    {
        [SerializeField] private TMP_Text label;

        public string AgentId { get; private set; }
        public event System.Action<string> Selected;

        private PinchGesture _gesture;

        private void Awake() => _gesture = GetComponent<PinchGesture>();
        private void OnEnable() => _gesture.OnSelect += HandleSelect;
        private void OnDisable() => _gesture.OnSelect -= HandleSelect;

        private void HandleSelect() => Selected?.Invoke(AgentId);

        public void SetAgent(AgentSummary agent)
        {
            AgentId = agent.AgentId;
            var glyph = AgentStateGlyphs.Glyph(agent.State);
            var color = AgentStateGlyphs.ColorHex(agent.State);
            var stateLabel = agent.State.ToString();
            label.text = $"<b>{agent.Provider}</b>\n<color={color}>{glyph}</color> {stateLabel}";
        }
    }
}
