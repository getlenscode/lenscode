using Aura.Interaction;
using UnityEngine;

namespace Aura.Panels
{
    /// <summary>
    /// A movable, resizable, pinnable world-space panel (spec §52 Panel Management).
    /// Wraps a <see cref="PinchGesture"/> on the panel's grab handle to expose:
    /// drag = reposition, pinch+hold = context menu (bring closer/push farther/
    /// pin/reset), pinch = no-op here (selection happens on the panel's own
    /// content, not the frame).
    /// </summary>
    public sealed class AuraPanel : MonoBehaviour
    {
        [Tooltip("Stable identifier used for layout persistence; must be unique per panel.")]
        [SerializeField] private string panelId;

        [Tooltip("Grab handle driving drag/hold gestures. Defaults to this GameObject.")]
        [SerializeField] private PinchGesture grabHandle;

        [Tooltip("Distance (m) moved per BringCloser/PushFarther step, along the view vector.")]
        [SerializeField] private float radialStepMeters = 0.15f;

        [SerializeField] private float minDistanceMeters = 0.4f;
        [SerializeField] private float maxDistanceMeters = 3.0f;

        public string PanelId => panelId;
        public bool Pinned { get; private set; }

        /// <summary>Fired on pinch+hold: a menu UI binds to this to show panel actions.</summary>
        public event System.Action ContextMenuRequested;

        private Pose _defaultPose;
        private Camera _headCamera;

        private void Awake()
        {
            if (string.IsNullOrEmpty(panelId))
                Debug.LogWarning($"AuraPanel on '{name}' has no panelId; layout will not persist.", this);
            if (grabHandle == null) grabHandle = GetComponent<PinchGesture>();
            _defaultPose = new Pose(transform.position, transform.rotation);
            _headCamera = Camera.main;
        }

        private void OnEnable()
        {
            if (grabHandle == null) return;
            grabHandle.OnDragMove += HandleDragMove;
            grabHandle.OnHoldMenu += HandleHoldMenu;
        }

        private void OnDisable()
        {
            if (grabHandle == null) return;
            grabHandle.OnDragMove -= HandleDragMove;
            grabHandle.OnHoldMenu -= HandleHoldMenu;
        }

        private void HandleDragMove(Vector3 worldDelta)
        {
            // Pinned panels must be explicitly unpinned before they can move
            // (spec §52 pin/unpin), so a stray drag never relocates a fixed panel.
            if (Pinned) return;
            transform.position += worldDelta;
        }

        private void HandleHoldMenu() => ContextMenuRequested?.Invoke();

        public void Pin() => Pinned = true;
        public void Unpin() => Pinned = false;

        /// <summary>Moves the panel further along the vector from the user's head.</summary>
        public void PushFarther() => StepRadial(radialStepMeters);

        /// <summary>Moves the panel closer along the vector from the user's head.</summary>
        public void BringCloser() => StepRadial(-radialStepMeters);

        private void StepRadial(float signedStep)
        {
            if (Pinned || _headCamera == null) return;
            var origin = _headCamera.transform.position;
            var offset = transform.position - origin;
            var distance = offset.magnitude;
            if (distance < 0.001f) return;

            var newDistance = Mathf.Clamp(distance + signedStep, minDistanceMeters, maxDistanceMeters);
            transform.position = origin + offset.normalized * newDistance;
        }

        public void ResetLayout()
        {
            transform.SetPositionAndRotation(_defaultPose.position, _defaultPose.rotation);
            Pinned = false;
        }

        /// <summary>Applies a previously persisted pose (spec §52 layout persistence).</summary>
        public void ApplyLayout(PanelLayoutData data)
        {
            transform.SetPositionAndRotation(data.Position, data.Rotation);
            Pinned = data.Pinned;
        }

        public PanelLayoutData CaptureLayout() => new PanelLayoutData
        {
            PanelId = panelId,
            Position = transform.position,
            Rotation = transform.rotation,
            Pinned = Pinned,
        };
    }
}
