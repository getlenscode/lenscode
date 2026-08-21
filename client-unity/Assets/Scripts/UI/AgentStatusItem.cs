using Aura.Interaction;
using UnityEngine;
using UnityEngine.UIElements;

namespace Aura.UI
{
    /// <summary>
    /// One entry in the persistent agent strip (spec §10): "Claude ● Working".
    /// Selectable via pinch to reveal the detail card. Renders through a UI
    /// Toolkit World Space UIDocument (AgentChip.uxml).
    /// </summary>
    [RequireComponent(typeof(PinchGesture))]
    public sealed class AgentStatusItem : MonoBehaviour
    {
        [SerializeField] private UIDocument uiDocument;

        private Label _nameLabel;
        private Label _stateLabel;
        private VisualElement _stateDot;

        public string AgentId { get; private set; }
        public event System.Action<string> Selected;

        private PinchGesture _gesture;

        private void Awake()
        {
            _gesture = GetComponent<PinchGesture>();
            if (uiDocument == null) uiDocument = GetComponentInChildren<UIDocument>();
            var root = uiDocument.rootVisualElement;
            _nameLabel = root.Q<Label>("AgentName");
            _stateLabel = root.Q<Label>("AgentState");
            _stateDot = root.Q<VisualElement>("StateDot");
        }

        private void OnEnable() => _gesture.OnSelect += HandleSelect;
        private void OnDisable() => _gesture.OnSelect -= HandleSelect;

        private void HandleSelect() => Selected?.Invoke(AgentId);

        public void SetAgent(AgentSummary agent)
        {
            AgentId = agent.AgentId;
            _nameLabel.text = agent.Provider;
            _stateLabel.text = $"{AgentStateGlyphs.Glyph(agent.State)} {agent.State}";

            foreach (AgentState s in System.Enum.GetValues(typeof(AgentState)))
                _stateDot.RemoveFromClassList($"dot--{s.ToString().ToLowerInvariant()}");
            _stateDot.AddToClassList($"dot--{agent.State.ToString().ToLowerInvariant()}");
        }
    }
}
