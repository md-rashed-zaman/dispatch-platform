import * as Dialog from "@radix-ui/react-dialog";
import { LoaderCircle } from "lucide-react";
import type { ButtonHTMLAttributes, ReactNode } from "react";
import type { Status } from "../api";
import { STATUS } from "../format";

export function StatusPill({ status }: { status: Status }) {
  const { label, className, attention } = STATUS[status];
  return (
    <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-0.5 text-xs font-medium ${className}`}>
      <span aria-hidden className={`size-1.5 rounded-full bg-current ${attention ? "pulse-dot" : ""}`} />
      {label}
    </span>
  );
}

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: "primary" | "secondary" | "destructive";
  loading?: boolean;
};

const VARIANTS = {
  primary: "bg-primary text-white hover:bg-primary-hover",
  secondary: "border border-border bg-card text-foreground hover:bg-muted",
  destructive: "bg-destructive text-white hover:bg-destructive-hover",
};

export function Button({ variant = "primary", loading, disabled, children, className = "", ...rest }: ButtonProps) {
  return (
    <button
      {...rest}
      disabled={disabled || loading}
      className={`inline-flex h-9 cursor-pointer items-center justify-center gap-2 rounded-md px-3.5 text-sm font-medium transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-60 max-sm:h-11 ${VARIANTS[variant]} ${className}`}>
      {loading && <LoaderCircle aria-hidden className="size-4 animate-spin" />}
      {children}
    </button>
  );
}

export function Panel({ title, children, action }: { title: string; children: ReactNode; action?: ReactNode }) {
  return (
    <section className="rounded-[10px] border border-border bg-card">
      <header className="flex items-center justify-between border-b border-border px-4 py-3">
        <h2 className="text-xs font-semibold uppercase tracking-[0.04em] text-muted-foreground">{title}</h2>
        {action}
      </header>
      <div className="p-4">{children}</div>
    </section>
  );
}

/** An error from the API, in words, next to what caused it. */
export function ErrorNote({ children }: { children: ReactNode }) {
  return (
    <p role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800 dark:border-red-500/30 dark:bg-red-500/10 dark:text-red-300">
      {children}
    </p>
  );
}

export function Skeleton({ className = "" }: { className?: string }) {
  return <div aria-hidden className={`animate-pulse rounded bg-muted motion-reduce:animate-none ${className}`} />;
}

export function ConfirmDialog(props: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description: ReactNode;
  children?: ReactNode;
  confirm: ReactNode;
}) {
  return (
    <Dialog.Root open={props.open} onOpenChange={props.onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-slate-950/40" />
        <Dialog.Content className="fixed z-50 left-1/2 top-1/2 w-[calc(100%-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 rounded-[10px] border border-border bg-card p-5 shadow-[0_8px_24px_rgb(15_23_42/0.12)]">
          <Dialog.Title className="text-lg font-semibold">{props.title}</Dialog.Title>
          <Dialog.Description className="mt-1 text-sm text-muted-foreground">{props.description}</Dialog.Description>
          {props.children}
          <div className="mt-5 flex justify-end gap-2">
            <Dialog.Close asChild>
              <Button variant="secondary">Keep it</Button>
            </Dialog.Close>
            {props.confirm}
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
