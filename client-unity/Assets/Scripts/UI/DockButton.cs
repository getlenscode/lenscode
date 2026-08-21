using Aura.Interaction;
using UnityEngine;
using UnityEngine.UIElements;

namespace Aura.UI
{
    /// <summary>
    /// One quick-dock button (spec §71: Code/Terminal/Diff/Git/Agents/Remote).
    /// Renders through a UI Toolkit World Space UIDocument (DockButton.uxml).
    /// </summary>
    [RequireComponent(typeof(PinchGesture))]
    public sealed class DockButton : MonoBehaviour
    {
        [SerializeField] private string viewId;
        [SerializeField] private UIDocument uiDocument;

        private Label _label;

        public string ViewId => viewId;
        public event System.Action<string> Selected;

        private PinchGesture _gesture;

        private void Awake()
        {
            _gesture = GetComponent<PinchGesture>();
            if (uiDocument == null) uiDocument = GetComponentInChildren<UIDocument>();
            _label = uiDocument.rootVisualElement.Q<Label>("DockLabel");
        }

        private void OnEnable() => _gesture.OnSelect += HandleSelect;
        private void OnDisable() => _gesture.OnSelect -= HandleSelect;

        private void HandleSelect() => Selected?.Invoke(viewId);

        public void SetHighlighted(bool active)
        {
            _label.EnableInClassList("dock-label--active", active);
        }
    }
}
