using UnityEngine;
using UnityEngine.XR.Interaction.Toolkit;
using UnityEngine.XR.Interaction.Toolkit.Interactables;

namespace LensCode.Interaction
{
    /// <summary>
    /// Translates XRI's raw hover/select events on an <see cref="XRBaseInteractable"/>
    /// into the XR-native interaction hierarchy from spec §9:
    ///
    ///   gaze/point  -> preview / highlight   (hover)
    ///   pinch       -> select / open / switch (quick release)
    ///   pinch+hold  -> advanced actions       (held past a threshold, no movement)
    ///   drag        -> move / assign          (moved past a threshold while held)
    ///
    /// Attach next to an <see cref="XRSimpleInteractable"/> (or any XRBaseInteractable);
    /// this component only classifies its events; it does not itself move anything.
    /// </summary>
    [RequireComponent(typeof(XRBaseInteractable))]
    public sealed class PinchGesture : MonoBehaviour
    {
        [Tooltip("Seconds a pinch must be held, without dragging, to count as pinch+hold.")]
        [SerializeField] private float holdThresholdSeconds = 0.45f;

        [Tooltip("World-space distance the pinch point must travel to count as a drag.")]
        [SerializeField] private float dragThresholdMeters = 0.03f;

        public event System.Action OnGazeEnter;
        public event System.Action OnGazeExit;
        /// <summary>Quick pinch-and-release: open/select/switch.</summary>
        public event System.Action OnSelect;
        /// <summary>Pinch held in place past the threshold: advanced/context actions.</summary>
        public event System.Action OnHoldMenu;
        public event System.Action<Vector3> OnDragStart;
        /// <summary>World-space delta since the previous frame.</summary>
        public event System.Action<Vector3> OnDragMove;
        public event System.Action OnDragEnd;

        private XRBaseInteractable _interactable;
        private Transform _pinchSource;
        private Vector3 _pressStartPos;
        private Vector3 _lastFramePos;
        private float _pressStartTime;
        private bool _pressed;
        private bool _dragging;
        private bool _holdFired;

        private void Awake() => _interactable = GetComponent<XRBaseInteractable>();

        private void OnEnable()
        {
            _interactable.hoverEntered.AddListener(HandleHoverEnter);
            _interactable.hoverExited.AddListener(HandleHoverExit);
            _interactable.selectEntered.AddListener(HandleSelectEnter);
            _interactable.selectExited.AddListener(HandleSelectExit);
        }

        private void OnDisable()
        {
            _interactable.hoverEntered.RemoveListener(HandleHoverEnter);
            _interactable.hoverExited.RemoveListener(HandleHoverExit);
            _interactable.selectEntered.RemoveListener(HandleSelectEnter);
            _interactable.selectExited.RemoveListener(HandleSelectExit);
        }

        private void HandleHoverEnter(HoverEnterEventArgs args) => OnGazeEnter?.Invoke();
        private void HandleHoverExit(HoverExitEventArgs args) => OnGazeExit?.Invoke();

        private void HandleSelectEnter(SelectEnterEventArgs args)
        {
            _pinchSource = args.interactorObject.transform;
            _pressStartPos = _lastFramePos = _pinchSource.position;
            _pressStartTime = Time.time;
            _pressed = true;
            _dragging = false;
            _holdFired = false;
        }

        private void HandleSelectExit(SelectExitEventArgs args)
        {
            if (!_pressed) return;
            if (_dragging)
            {
                OnDragEnd?.Invoke();
            }
            else if (!_holdFired)
            {
                // Never fires both a hold action and a select on the same press.
                OnSelect?.Invoke();
            }
            _pressed = false;
            _dragging = false;
            _pinchSource = null;
        }

        private void Update()
        {
            if (!_pressed || _pinchSource == null) return;

            var pos = _pinchSource.position;

            if (!_dragging)
            {
                var travelled = Vector3.Distance(_pressStartPos, pos);
                if (travelled >= dragThresholdMeters)
                {
                    _dragging = true;
                    OnDragStart?.Invoke(pos);
                }
                else if (!_holdFired && Time.time - _pressStartTime >= holdThresholdSeconds)
                {
                    _holdFired = true;
                    OnHoldMenu?.Invoke();
                }
            }

            if (_dragging)
            {
                var delta = pos - _lastFramePos;
                if (delta.sqrMagnitude > 0f) OnDragMove?.Invoke(delta);
            }

            _lastFramePos = pos;
        }
    }
}
