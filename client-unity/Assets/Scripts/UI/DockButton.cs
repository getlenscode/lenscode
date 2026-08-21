using Aura.Interaction;
using TMPro;
using UnityEngine;

namespace Aura.UI
{
    /// <summary>One quick-dock button (spec §71: Code/Terminal/Diff/Git/Agents/Remote).</summary>
    [RequireComponent(typeof(PinchGesture))]
    public sealed class DockButton : MonoBehaviour
    {
        [SerializeField] private string viewId;
        [SerializeField] private TMP_Text label;
        [SerializeField] private GameObject activeHighlight;

        public string ViewId => viewId;
        public event System.Action<string> Selected;

        private PinchGesture _gesture;

        private void Awake() => _gesture = GetComponent<PinchGesture>();
        private void OnEnable() => _gesture.OnSelect += HandleSelect;
        private void OnDisable() => _gesture.OnSelect -= HandleSelect;

        private void HandleSelect() => Selected?.Invoke(viewId);

        public void SetHighlighted(bool active)
        {
            if (activeHighlight != null) activeHighlight.SetActive(active);
        }

        private void Reset()
        {
            if (label == null) label = GetComponentInChildren<TMP_Text>();
        }
    }
}
