// SVG icons from docs/design, as components. All decorative (aria-hidden).

type P = { size?: number; color?: string };

export function RegistryMark({ size = 28, inverse = false }: { size?: number; inverse?: boolean }) {
  return (
    <svg width={size} height={size} viewBox="0 0 28 28" aria-hidden="true">
      <rect x="1" y="1" width="26" height="26" rx="7" fill={inverse ? "#FFFFFF" : "#2443E6"} />
      <path
        d="M8 18.5 L12.5 9.5 L14 12.5 L15.5 9.5 L20 18.5"
        fill="none"
        stroke={inverse ? "#2443E6" : "#FFFFFF"}
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function LanternMark() {
  return (
    <svg width="30" height="30" viewBox="0 0 30 30" aria-hidden="true">
      <path d="M11 4h8M15 4v3" stroke="#FFB547" strokeWidth="2" strokeLinecap="round" />
      <rect x="7" y="7" width="16" height="19" rx="5" fill="none" stroke="#FFB547" strokeWidth="2" />
      <circle cx="15" cy="16.5" r="4" fill="#FFB547" />
    </svg>
  );
}

function Stroke({ size = 24, color = "currentColor", width = 2, children }: P & { width?: number; children: React.ReactNode }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      aria-hidden="true"
      fill="none"
      stroke={color}
      strokeWidth={width}
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      {children}
    </svg>
  );
}

export const AgentIcon = (p: P) => (
  <Stroke size={22} {...p}>
    <rect x="4" y="7" width="16" height="12" rx="3" />
    <path d="M12 3v4M9 12h.01M15 12h.01M9 16h6" />
  </Stroke>
);

export const AgentChipIcon = () => (
  <Stroke size={14} color="#FFFFFF" width={2.2}>
    <rect x="4" y="7" width="16" height="12" rx="3" />
    <path d="M12 3v4M9 16h6" />
  </Stroke>
);

export const CheckIcon = ({ size = 14, color = "currentColor", width = 3 }: P & { width?: number }) => (
  <Stroke size={size} color={color} width={width}>
    <path d="M5 12.5l4.5 4.5L19 7.5" />
  </Stroke>
);

export const BackIcon = () => (
  <Stroke size={16}>
    <path d="M15 6l-6 6 6 6" />
  </Stroke>
);

export const ClockIcon = () => (
  <Stroke size={18} color="#8A5300">
    <circle cx="12" cy="12" r="9" />
    <path d="M12 7v5l3 2" />
  </Stroke>
);

export const CopyIcon = () => (
  <Stroke size={16} color="#FFFFFF">
    <rect x="8" y="8" width="12" height="12" rx="2" />
    <path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3" />
  </Stroke>
);

export const ArrowIcon = () => (
  <Stroke size={18} color="#131117" width={2.2}>
    <path d="M5 12h14M13 6l6 6-6 6" />
  </Stroke>
);

export const WalletIcon = () => (
  <Stroke size={26} color="#131117">
    <rect x="3" y="6" width="18" height="13" rx="3" />
    <path d="M16 12.5h.01M3 10h18" />
  </Stroke>
);

export const OpenWalletIcon = () => (
  <Stroke size={22} color="#131117">
    <rect x="3" y="5" width="18" height="14" rx="3" />
    <path d="M7 10h6M7 14h4M16 12.5l1.2 1.2 2.3-2.4" />
  </Stroke>
);
