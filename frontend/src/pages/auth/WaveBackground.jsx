import './WaveBackground.css';

export default function WaveBackground() {
  return (
    <div className="wave-container" aria-hidden="true">
      {/* Light, airy base gradient */}
      <div className="wave-base" />

      {/* Animated wave layers - full left side coverage */}
      <svg className="wave-svg" viewBox="0 0 1 1" preserveAspectRatio="none">
        <defs>
          {/* Wave 1 - Light sky blue (tallest, leftmost) */}
          <linearGradient id="waveGrad1" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#e0f2fe" stopOpacity="0.9" />
            <stop offset="50%" stopColor="#bae6fd" stopOpacity="0.8" />
            <stop offset="100%" stopColor="#7dd3fc" stopOpacity="0.7" />
          </linearGradient>

          {/* Wave 2 - Light periwinkle/indigo */}
          <linearGradient id="waveGrad2" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#e0e7ff" stopOpacity="0.85" />
            <stop offset="50%" stopColor="#c7d2fe" stopOpacity="0.75" />
            <stop offset="100%" stopColor="#818cf8" stopOpacity="0.55" />
          </linearGradient>

          {/* Wave 3 - Soft lavender */}
          <linearGradient id="waveGrad3" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#ede9fe" stopOpacity="0.75" />
            <stop offset="50%" stopColor="#ddd6fe" stopOpacity="0.65" />
            <stop offset="100%" stopColor="#a78bfa" stopOpacity="0.5" />
          </linearGradient>

          {/* Wave 4 - Light lilac */}
          <linearGradient id="waveGrad4" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#faf5ff" stopOpacity="0.6" />
            <stop offset="50%" stopColor="#f3e8ff" stopOpacity="0.5" />
            <stop offset="100%" stopColor="#d8b4fe" stopOpacity="0.4" />
          </linearGradient>

          {/* Wave 5 - Soft rose */}
          <linearGradient id="waveGrad5" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#fff1f2" stopOpacity="0.5" />
            <stop offset="50%" stopColor="#ffe4e6" stopOpacity="0.35" />
            <stop offset="100%" stopColor="#fda4af" stopOpacity="0.25" />
          </linearGradient>

          {/* Shimmer highlight - bright white sweep */}
          <linearGradient id="waveShimmer" x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="rgba(255,255,255,0)" stopOpacity="0" />
            <stop offset="45%" stopColor="rgba(255,255,255,0)" stopOpacity="0" />
            <stop offset="50%" stopColor="rgba(255,255,255,0.6)" stopOpacity="0.7" />
            <stop offset="55%" stopColor="rgba(255,255,255,0.35)" stopOpacity="0.5" />
            <stop offset="60%" stopColor="rgba(255,255,255,0)" stopOpacity="0" />
            <stop offset="100%" stopColor="rgba(255,255,255,0)" stopOpacity="0" />
          </linearGradient>

          {/* Radial glow */}
          <radialGradient id="orbitalGlow" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor="rgba(165,180,252,0.25)" stopOpacity="0.4" />
            <stop offset="40%" stopColor="rgba(167,139,250,0.15)" stopOpacity="0.25" />
            <stop offset="70%" stopColor="rgba(139,92,246,0.1)" stopOpacity="0.15" />
            <stop offset="100%" stopColor="rgba(139,92,246,0)" stopOpacity="0" />
          </radialGradient>

          {/* Orbital ring glow */}
          <radialGradient id="orbitalRing" cx="50%" cy="50%" r="50%">
            <stop offset="0%" stopColor="rgba(56,189,248,0)" stopOpacity="0" />
            <stop offset="60%" stopColor="rgba(56,189,248,0)" stopOpacity="0" />
            <stop offset="70%" stopColor="rgba(56,189,248,0.15)" stopOpacity="0.2" />
            <stop offset="80%" stopColor="rgba(99,102,241,0.1)" stopOpacity="0.15" />
            <stop offset="90%" stopColor="rgba(139,92,246,0.08)" stopOpacity="0.1" />
            <stop offset="100%" stopColor="rgba(139,92,246,0)" stopOpacity="0" />
          </radialGradient>
        </defs>

        {/* Wave 1 - Soft sky blue (tallest, covers most left side) */}
        <path
          className="wave-path wave-1"
          d="M0,0 C0.2,0.15 0.4,0.35 0.6,0.2 C0.8,0.1 0.9,0.35 1,0.2 L1,1 L0,1 Z"
          fill="url(#waveGrad1)"
        />

        {/* Wave 2 */}
        <path
          className="wave-path wave-2"
          d="M0,0 C0.22,0.1 0.4,0.45 0.6,0.1 C0.8,0.05 0.9,0.45 1,0.2 L1,1 L0,1 Z"
          fill="url(#waveGrad2)"
        />

        {/* Wave 3 */}
        <path
          className="wave-path wave-3"
          d="M0,0 C0.18,0.2 0.36,0.65 0.54,0.35 C0.72,0.1 0.9,0.65 1,0.35 L1,1 L0,1 Z"
          fill="url(#waveGrad3)"
        />

        {/* Wave 4 */}
        <path
          className="wave-path wave-4"
          d="M0,0 C0.3,0.3 0.55,0.7 0.8,0.4 C1.05,0.2 1.3,0.55 1,0.4 L1,1 L0,1 Z"
          fill="url(#waveGrad4)"
        />

        {/* Wave 5 */}
        <path
          className="wave-path wave-5"
          d="M0,0 C0.25,0.35 0.5,0.75 0.75,0.45 C1,0.15 1.25,0.65 1,0.4 L1,1 L0,1 Z"
          fill="url(#waveGrad5)"
        />

        {/* Shimmer sweep - bright white */}
        <path
          className="wave-shimmer"
          d="M0,0 C0.2,0.15 0.4,0.35 0.6,0.2 C0.8,0.1 0.9,0.35 1,0.2 L1,0.45 L0,0.45 Z"
          fill="url(#waveShimmer)"
        />

        {/* Central glow */}
        <circle
          className="pulsar-glow"
          cx="0.5"
          cy="0.5"
          r="0.25"
          fill="url(#orbitalGlow)"
        />

        {/* Orbital rings */}
        <circle
          className="orbital-ring ring-1"
          cx="0.5"
          cy="0.5"
          r="0.3"
          fill="none"
          stroke="url(#orbitalRing)"
          strokeWidth="0.002"
        />
        <circle
          className="orbital-ring ring-2"
          cx="0.5"
          cy="0.5"
          r="0.4"
          fill="none"
          stroke="url(#orbitalRing)"
          strokeWidth="0.0015"
        />
        <circle
          className="orbital-ring ring-3"
          cx="0.5"
          cy="0.5"
          r="0.5"
          fill="none"
          stroke="url(#orbitalRing)"
          strokeWidth="0.001"
        />
      </svg>

      {/* Subtle vignette */}
      <div className="wave-vignette" />

      {/* Central glow */}
      <div className="wave-glow" />

      {/* Floating particles */}
      <div className="wave-particles" />
    </div>
  );
}