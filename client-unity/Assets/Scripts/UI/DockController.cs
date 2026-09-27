using UnityEngine;

namespace LensCode.UI
{
    /// <summary>
    /// Quick dock (spec §71): exactly one dominant primary panel at a time,
    /// switched by pinching a dock button — no keyboard required (spec §67).
    /// </summary>
    public sealed class DockController : MonoBehaviour
    {
        [SerializeField] private DockButton[] buttons;
        [SerializeField] private string initialViewId = "code";

        public event System.Action<string> ViewChanged;
        public string ActiveViewId { get; private set; }

        private void OnEnable()
        {
            foreach (var b in buttons)
            {
                if (b != null) b.Selected += HandleButtonSelected;
            }
        }

        private void OnDisable()
        {
            foreach (var b in buttons)
            {
                if (b != null) b.Selected -= HandleButtonSelected;
            }
        }

        private void Start() => SetActiveView(initialViewId);

        private void HandleButtonSelected(string viewId) => SetActiveView(viewId);

        public void SetActiveView(string viewId)
        {
            ActiveViewId = viewId;
            foreach (var b in buttons)
            {
                if (b != null) b.SetHighlighted(b.ViewId == viewId);
            }
            ViewChanged?.Invoke(viewId);
        }
    }
}
