type BadgeVariant =
  | 'BUY'
  | 'SELL'
  | 'HOLD'
  | 'RUNNING'
  | 'STOPPED'
  | 'COMPLETED'
  | 'FAILED'
  | 'AVAILABLE'
  | 'SKELETON'
  | 'ACCEPTABLE'
  | 'CAUTION'
  | 'OVERFITTING'
  | 'EMERGENCY_STOPPED'
  | 'PENDING'
  | 'SUBMITTED'
  | 'PARTIAL_FILLED'
  | 'FILLED'
  | 'CANCELLED'
  | 'UP'
  | 'DEGRADED'
  | 'DOWN'
  | 'LONG'
  | 'SHORT'
  | 'MARKET'
  | 'LIMIT'
  | 'default';

interface BadgeProps {
  variant?: BadgeVariant;
  children: React.ReactNode;
  className?: string;
}

const variantClasses: Record<BadgeVariant, string> = {
  BUY: 'bg-emerald-900/50 text-emerald-400 border border-emerald-700',
  SELL: 'bg-red-900/50 text-red-400 border border-red-700',
  HOLD: 'bg-yellow-900/50 text-yellow-400 border border-yellow-700',
  RUNNING: 'bg-blue-900/50 text-blue-400 border border-blue-700',
  STOPPED: 'bg-slate-700/50 text-slate-400 border border-slate-600',
  COMPLETED: 'bg-emerald-900/50 text-emerald-400 border border-emerald-700',
  FAILED: 'bg-red-900/50 text-red-400 border border-red-700',
  AVAILABLE: 'bg-emerald-900/50 text-emerald-400 border border-emerald-700',
  SKELETON: 'bg-slate-700/50 text-slate-400 border border-slate-600',
  ACCEPTABLE: 'bg-emerald-900/50 text-emerald-400 border border-emerald-700',
  CAUTION: 'bg-yellow-900/50 text-yellow-400 border border-yellow-700',
  OVERFITTING: 'bg-red-900/50 text-red-400 border border-red-700',
  default: 'bg-slate-700/50 text-slate-400 border border-slate-600',
};

function Badge({ variant = 'default', children, className = '' }: BadgeProps) {
  return (
    <span
      className={[
        'inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium',
        variantClasses[variant],
        className,
      ].join(' ')}
    >
      {children}
    </span>
  );
}

export { Badge };
export type { BadgeVariant };
