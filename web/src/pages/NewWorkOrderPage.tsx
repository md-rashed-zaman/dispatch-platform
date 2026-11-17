import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Check, Plus, Sparkles } from "lucide-react";
import { useState, type FormEvent, type ReactNode } from "react";
import { Link, useNavigate } from "react-router";
import { ApiError, post, type WorkOrder } from "../api";
import { Button, ErrorNote } from "../components/ui";

type Form = {
  title: string;
  description: string;
  siteAddress: string;
  latitude: string;
  longitude: string;
  skills: string;
  startsAt: string;
  endsAt: string;
  budget: string;
  checklist: string;
};

const EMPTY: Form = {
  title: "",
  description: "",
  siteAddress: "",
  latitude: "",
  longitude: "",
  skills: "",
  startsAt: "",
  endsAt: "",
  budget: "",
  checklist: "",
};

/** Field names the API reports (errors[].field) mapped to the form field that shows them. */
const API_FIELD: Record<string, keyof Form> = {
  title: "title",
  description: "description",
  siteAddress: "siteAddress",
  "site.latitude": "latitude",
  "site.longitude": "longitude",
  requiredSkills: "skills",
  "timeWindow.startsAt": "startsAt",
  "timeWindow.endsAt": "endsAt",
  timeWindow: "endsAt",
  "timeWindow.endAfterStart": "endsAt",
  "budget.minor": "budget",
  checklist: "checklist",
};

/** Bean Validation messages are for developers ("must match [a-z0-9]+..."); say the rule instead. */
function friendly(field: keyof Form, message: string): string {
  if (field === "skills" && message.startsWith("must match")) {
    return "Skills use lowercase letters, numbers and dashes, like pos-terminal.";
  }
  return message.charAt(0).toUpperCase() + message.slice(1) + ".";
}

const lines = (text: string) => text.split("\n").map((l) => l.trim()).filter(Boolean);
const skillCodes = (text: string) => text.split(",").map((s) => s.trim().toLowerCase().replace(/\s+/g, "-")).filter(Boolean);

function validate(f: Form): Partial<Record<keyof Form, string>> {
  const errors: Partial<Record<keyof Form, string>> = {};
  const lat = Number(f.latitude);
  const lon = Number(f.longitude);
  if (!f.title.trim()) errors.title = "Give the job a short title.";
  if (!f.description.trim()) errors.description = "Say what's wrong and what to do.";
  if (!f.siteAddress.trim()) errors.siteAddress = "Where should the technician go?";
  if (f.latitude === "" || Number.isNaN(lat) || lat < -90 || lat > 90) errors.latitude = "Latitude, like 23.7930.";
  if (f.longitude === "" || Number.isNaN(lon) || lon < -180 || lon > 180) errors.longitude = "Longitude, like 90.4130.";
  if (skillCodes(f.skills).length === 0) errors.skills = "At least one skill, like cctv.";
  if (!f.startsAt) errors.startsAt = "When can the work start?";
  if (!f.endsAt) errors.endsAt = "By when must it be done?";
  else if (f.startsAt && new Date(f.endsAt) <= new Date(f.startsAt)) errors.endsAt = "Must end after it starts.";
  if (!(Number(f.budget) > 0)) errors.budget = "A budget in taka, more than 0.";
  if (lines(f.checklist).length === 0) errors.checklist = "At least one thing the technician must do or show.";
  return errors;
}

type Suggestion = { skills: string[]; available: boolean };

/** POST /api/work-orders/draft-from-text */
type Intake = {
  draft: {
    title: string | null;
    description: string;
    skills: string[];
    urgency: "NORMAL" | "URGENT" | null;
    siteAddress: string | null;
    timeWindow: { startsAt: string; endsAt: string } | null;
    checklist: string[];
  };
  missing: string[];
  available: boolean;
};

const MISSING_LABEL: Record<string, string> = {
  title: "title",
  skills: "skills",
  siteAddress: "site address",
  timeWindow: "time window",
  checklist: "checklist",
  siteLocation: "site location",
};

/** An instant as the value of a datetime-local input, in the browser's time zone. */
function localInput(iso: string): string {
  const d = new Date(iso);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export function NewWorkOrderPage() {
  const [form, setForm] = useState<Form>(EMPTY);
  const [suggestion, setSuggestion] = useState<Suggestion | null>(null);
  const [message, setMessage] = useState("");
  const [aiFilled, setAiFilled] = useState<Set<keyof Form>>(new Set());
  const [intakeStatus, setIntakeStatus] = useState("");
  const [errors, setErrors] = useState<Partial<Record<keyof Form, string>>>({});
  const [publish, setPublish] = useState(true);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const create = useMutation({
    mutationFn: async (andPublish: boolean) => {
      const created = await post<WorkOrder>("/api/work-orders", {
        title: form.title.trim(),
        description: form.description.trim(),
        siteAddress: form.siteAddress.trim(),
        site: { latitude: Number(form.latitude), longitude: Number(form.longitude) },
        requiredSkills: skillCodes(form.skills),
        timeWindow: { startsAt: new Date(form.startsAt).toISOString(), endsAt: new Date(form.endsAt).toISOString() },
        budget: { minor: Math.round(Number(form.budget) * 100), currency: "BDT" },
        checklist: lines(form.checklist),
      });
      if (andPublish) await post(`/api/work-orders/${created.id}/publish`);
      return created;
    },
    onSuccess: (created) => {
      void queryClient.invalidateQueries({ queryKey: ["work-orders"] });
      navigate(`/work-orders/${created.id}`);
    },
    onError: (error) => {
      if (error instanceof ApiError && error.problem.errors) {
        const fromApi: Partial<Record<keyof Form, string>> = {};
        for (const e of error.problem.errors) {
          const field = API_FIELD[e.field] ?? API_FIELD[e.field.replace(/\[\d*\]$/, "")]; // "requiredSkills[]"
          if (field) fromApi[field] = friendly(field, e.message);
        }
        setErrors(fromApi);
        const first = Object.keys(EMPTY).find((name) => fromApi[name as keyof Form]);
        if (first) setTimeout(() => document.getElementById(first)?.focus()); // after the button re-enables
      }
    },
  });

  // A button, not on every keystroke: each suggestion is a metered model call (design-system screens.md §3).
  const suggest = useMutation({
    mutationFn: () =>
      post<Suggestion>("/api/ai/skill-suggestions", { title: form.title.trim(), description: form.description.trim() }),
    onSuccess: setSuggestion,
    onError: () => setSuggestion({ skills: [], available: false }), // the form works without it
  });
  // Fills only empty fields: what the dispatcher typed is theirs (design-system screens.md §3, Describe the job).
  const readMessage = useMutation({
    mutationFn: () => post<Intake>("/api/work-orders/draft-from-text", { text: message.trim() }),
    onSuccess: ({ draft, missing, available }) => {
      const values: Partial<Form> = { description: draft.description };
      if (available) {
        if (draft.title) values.title = draft.title;
        if (draft.siteAddress) values.siteAddress = draft.siteAddress;
        if (draft.skills.length) values.skills = draft.skills.join(", ");
        if (draft.timeWindow) {
          values.startsAt = localInput(draft.timeWindow.startsAt);
          values.endsAt = localInput(draft.timeWindow.endsAt);
        }
        if (draft.checklist.length) values.checklist = draft.checklist.join("\n");
      }
      const filled = (Object.keys(values) as (keyof Form)[]).filter((name) => !form[name].trim());
      setForm({ ...form, ...Object.fromEntries(filled.map((name) => [name, values[name]])) });
      setAiFilled(new Set(filled));
      setSuggestion(null);
      if (!available) {
        setIntakeStatus("Couldn't read the message right now. Fill the form yourself; your text is in Description.");
        return;
      }
      const todo = missing.map((m) => MISSING_LABEL[m] ?? m).join(", ");
      const urgent = draft.urgency === "URGENT" ? " Sounds urgent: the time window starts within the hour." : "";
      setIntakeStatus(`Filled ${filled.length} field${filled.length === 1 ? "" : "s"} from the message. Still to fill: ${todo}.${urgent}`);
    },
    onError: () => setIntakeStatus("Couldn't read the message right now. Fill the form yourself."),
  });

  const chosen = skillCodes(form.skills);
  const addSkill = (code: string) => {
    if (!chosen.includes(code)) setForm({ ...form, skills: [...chosen, code].join(", ") });
  };

  const submit = (event: FormEvent, andPublish: boolean) => {
    event.preventDefault();
    const found = validate(form);
    setErrors(found);
    setPublish(andPublish);
    const first = Object.keys(EMPTY).find((name) => found[name as keyof Form]);
    if (first) {
      document.getElementById(first)?.focus(); // keyboard and screen reader users land on the first problem
      return;
    }
    create.mutate(andPublish);
  };

  const field = (name: keyof Form) => ({
    id: name,
    name,
    value: form[name],
    onChange: (e: { target: { value: string } }) => {
      setForm({ ...form, [name]: e.target.value });
      if (name === "title" || name === "description") setSuggestion(null); // they described other text
      if (aiFilled.has(name)) setAiFilled(new Set([...aiFilled].filter((n) => n !== name))); // theirs now
    },
    "aria-invalid": !!errors[name],
    "aria-describedby": errors[name] ? `${name}-error` : undefined,
    "aria-description": aiFilled.has(name) ? "Filled by AI from the message" : undefined,
    className: `w-full rounded-md border px-3 py-2 text-base transition-colors ${aiFilled.has(name) ? "bg-primary-soft" : "bg-card"} ${errors[name] ? "border-destructive" : "border-border"}`,
  });

  const unexplained = create.error && !(create.error instanceof ApiError && create.error.problem.errors);

  return (
    <div className="mx-auto max-w-3xl">
      <Link to="/work-orders" className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft aria-hidden className="size-4" /> Work orders
      </Link>
      <h1 className="mt-3 text-2xl font-semibold tracking-tight">New work order</h1>
      <p className="mt-1 text-sm text-muted-foreground">Published work orders go to the nearest verified technicians with the skills.</p>

      <section aria-labelledby="describe-heading" className="mt-6 rounded-[10px] border border-border bg-card p-5 sm:p-6">
        <h2 id="describe-heading" className="text-sm font-medium">
          Describe the job <span className="font-normal text-muted-foreground">(optional)</span>
        </h2>
        <label htmlFor="message" className="sr-only">Message from the branch</label>
        <textarea
          id="message"
          value={message}
          onChange={(e) => setMessage(e.target.value)}
          rows={2}
          maxLength={2000}
          placeholder="Gulshan office printer shows E05, need someone tomorrow morning"
          className="mt-2 w-full rounded-md border border-border bg-card px-3 py-2 text-base"
        />
        <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm text-muted-foreground">Paste the branch's message; AI fills the form below for you to check.</p>
          <Button type="button" variant="secondary" loading={readMessage.isPending} disabled={!message.trim()} onClick={() => readMessage.mutate()}>
            {!readMessage.isPending && <Sparkles aria-hidden className="size-4" />}
            {readMessage.isPending ? "Reading…" : "Fill the form"}
          </Button>
        </div>
        <p role="status" aria-atomic="true" className={`text-sm text-muted-foreground ${intakeStatus ? "mt-3" : ""}`}>
          {intakeStatus}
        </p>
      </section>

      <form noValidate onSubmit={(e) => submit(e, true)} className="mt-6 space-y-6 rounded-[10px] border border-border bg-card p-5 sm:p-6">
        {unexplained && <ErrorNote>{create.error.message}</ErrorNote>}

        <Field label="Title" name="title" error={errors.title}>
          <input {...field("title")} placeholder="CCTV camera offline" maxLength={200} />
        </Field>
        <Field label="Description" name="description" error={errors.description}>
          <textarea {...field("description")} rows={3} placeholder="Camera 3 in the vault shows no signal." />
        </Field>

        <Field label="Site address" name="siteAddress" error={errors.siteAddress}>
          <input {...field("siteAddress")} placeholder="Gulshan Avenue, Dhaka" />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Latitude" name="latitude" error={errors.latitude} hint="Dhaka is around 23.7 to 23.9">
            <input {...field("latitude")} inputMode="decimal" placeholder="23.7930" />
          </Field>
          <Field label="Longitude" name="longitude" error={errors.longitude} hint="Dhaka is around 90.3 to 90.5">
            <input {...field("longitude")} inputMode="decimal" placeholder="90.4130" />
          </Field>
        </div>

        <Field
          label="Required skills"
          name="skills"
          error={errors.skills}
          hint="Comma-separated, like cctv, networking"
          after={<SkillSuggestions suggestion={suggestion} chosen={chosen} onAdd={addSkill} />}
          action={
            <Button
              type="button"
              variant="secondary"
              className="h-8 px-2.5 text-xs max-sm:h-11"
              loading={suggest.isPending}
              disabled={!form.title.trim() || !form.description.trim()}
              onClick={() => suggest.mutate()}>
              {!suggest.isPending && <Sparkles aria-hidden className="size-3.5" />}
              {suggest.isPending ? "Suggesting…" : "Suggest from description"}
            </Button>
          }>
          <input {...field("skills")} placeholder="cctv" autoCapitalize="none" />
        </Field>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="Can start" name="startsAt" error={errors.startsAt}>
            <input {...field("startsAt")} type="datetime-local" />
          </Field>
          <Field label="Must be done by" name="endsAt" error={errors.endsAt}>
            <input {...field("endsAt")} type="datetime-local" />
          </Field>
        </div>

        <Field label="Budget (BDT)" name="budget" error={errors.budget}>
          <input {...field("budget")} inputMode="decimal" placeholder="3000" className={`${field("budget").className} data sm:max-w-48`} />
        </Field>

        <Field label="Checklist" name="checklist" error={errors.checklist} hint="One item per line: what the technician must do or show">
          <textarea {...field("checklist")} rows={3} placeholder={"Photo of the working camera\nPhoto of the recorder screen"} />
        </Field>

        <div className="flex flex-wrap justify-end gap-2 border-t border-border pt-5">
          <Button type="button" variant="secondary" loading={create.isPending && !publish} disabled={create.isPending} onClick={(e) => submit(e, false)}>
            Save as draft
          </Button>
          <Button type="submit" loading={create.isPending && publish} disabled={create.isPending}>
            Publish
          </Button>
        </div>
      </form>
    </div>
  );
}

function SkillSuggestions(props: { suggestion: Suggestion | null; chosen: string[]; onAdd: (code: string) => void }) {
  const { suggestion, chosen, onAdd } = props;
  let message = "";
  if (suggestion && !suggestion.available) message = "Suggestions aren't available right now. Type the skills yourself.";
  else if (suggestion && suggestion.skills.length === 0) message = "No skill on our list fits. Type the skills yourself.";
  else if (suggestion) {
    const n = suggestion.skills.length;
    message = `${n} suggested skill${n === 1 ? "" : "s"}: ${suggestion.skills.join(", ")}`;
  }
  return (
    // Always mounted, so screen readers hear the status when it changes; no space taken until there's a message.
    <div className={`flex flex-wrap items-center gap-2 ${suggestion ? "mt-2" : ""}`}>
      {/* One atomic status message for screen readers; the pills below are the visible version. */}
      <span role="status" aria-atomic="true" className={suggestion?.skills.length ? "sr-only" : "text-sm text-muted-foreground"}>
        {message}
      </span>
      {suggestion && suggestion.skills.length > 0 && (
        <>
          <span aria-hidden className="text-sm text-muted-foreground">Suggested by AI:</span>
          {suggestion.skills.map((code) => {
            const added = chosen.includes(code);
            return (
              <button
                key={code}
                type="button"
                disabled={added}
                onClick={() => onAdd(code)}
                aria-label={added ? `Skill ${code} added` : `Add skill ${code}`}
                className="data inline-flex h-7 cursor-pointer items-center gap-1 rounded-full border border-border bg-card px-2.5 text-xs transition-colors duration-150 hover:bg-muted disabled:cursor-default disabled:border-transparent disabled:bg-primary-soft disabled:text-link max-sm:h-11">
                {added ? <Check aria-hidden className="size-3.5" /> : <Plus aria-hidden className="size-3.5" />}
                {code}
              </button>
            );
          })}
        </>
      )}
    </div>
  );
}

function Field(props: {
  label: string;
  name: string;
  error?: string;
  hint?: string;
  action?: ReactNode;
  after?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div>
      <div className={`flex flex-wrap items-center justify-between gap-2 ${props.action ? "min-h-8" : ""}`}>
        <label htmlFor={props.name} className="block text-sm font-medium">{props.label}</label>
        {props.action}
      </div>
      <div className="mt-1.5">{props.children}</div>
      {props.error ? (
        <p id={`${props.name}-error`} className="mt-1.5 text-sm text-destructive dark:text-red-400">{props.error}</p>
      ) : (
        props.hint && <p className="mt-1.5 text-sm text-muted-foreground">{props.hint}</p>
      )}
      {props.after}
    </div>
  );
}
