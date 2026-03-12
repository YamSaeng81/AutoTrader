import { forwardRef } from 'react';

interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode;
  padding?: 'none' | 'sm' | 'md' | 'lg';
}

const paddingClasses = {
  none: '',
  sm: 'p-3',
  md: 'p-4',
  lg: 'p-6',
};

const Card = forwardRef<HTMLDivElement, CardProps>(
  ({ children, padding = 'md', className = '', ...props }, ref) => {
    return (
      <div
        ref={ref}
        className={[
          'rounded-lg border border-slate-700 bg-slate-800 shadow-sm',
          'dark:border-slate-700 dark:bg-slate-800',
          paddingClasses[padding],
          className,
        ].join(' ')}
        {...props}
      >
        {children}
      </div>
    );
  }
);

Card.displayName = 'Card';

interface CardHeaderProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode;
}

function CardHeader({ children, className = '', ...props }: CardHeaderProps) {
  return (
    <div
      className={['mb-4 flex items-center justify-between', className].join(' ')}
      {...props}
    >
      {children}
    </div>
  );
}

interface CardTitleProps extends React.HTMLAttributes<HTMLHeadingElement> {
  children: React.ReactNode;
}

function CardTitle({ children, className = '', ...props }: CardTitleProps) {
  return (
    <h3
      className={['text-base font-semibold text-slate-100 dark:text-slate-100', className].join(
        ' '
      )}
      {...props}
    >
      {children}
    </h3>
  );
}

interface CardBodyProps extends React.HTMLAttributes<HTMLDivElement> {
  children: React.ReactNode;
}

function CardBody({ children, className = '', ...props }: CardBodyProps) {
  return (
    <div className={className} {...props}>
      {children}
    </div>
  );
}

export { Card, CardHeader, CardTitle, CardBody };
