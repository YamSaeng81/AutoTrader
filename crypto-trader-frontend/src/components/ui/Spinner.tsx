import { Loader2 } from 'lucide-react';

type SpinnerSize = 'sm' | 'md' | 'lg';

interface SpinnerProps {
  size?: SpinnerSize;
  className?: string;
  label?: string;
}

const sizeClasses: Record<SpinnerSize, string> = {
  sm: 'w-4 h-4',
  md: 'w-6 h-6',
  lg: 'w-10 h-10',
};

function Spinner({ size = 'md', className = '', label }: SpinnerProps) {
  return (
    <div className={['flex items-center justify-center gap-2', className].join(' ')}>
      <Loader2
        className={['animate-spin text-indigo-400', sizeClasses[size]].join(' ')}
        aria-hidden="true"
      />
      {label && (
        <span className="text-sm text-slate-400 dark:text-slate-400">{label}</span>
      )}
    </div>
  );
}

function FullPageSpinner({ label = '로딩 중...' }: { label?: string }) {
  return (
    <div className="flex h-64 w-full items-center justify-center">
      <Spinner size="lg" label={label} />
    </div>
  );
}

export { Spinner, FullPageSpinner };
