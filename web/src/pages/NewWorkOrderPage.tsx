import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft } from "lucide-react";
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

export function NewWorkOrderPage() {
  const [form, setForm] = useState<Form>(EMPTY);
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
    onChange: (e: { target: { value: string } }) => setForm({ ...form, [name]: e.target.value }),
    "aria-invalid": !!errors[name],
    "aria-describedby": errors[name] ? `${name}-error` : undefined,
    className: `w-full rounded-md border bg-card px-3 py-2 text-base transition-colors ${errors[name] ? "border-destructive" : "border-border"}`,
  });

  const unexplained = create.error && !(create.error instanceof ApiError && create.error.problem.errors);

  return (
    <div className="mx-auto max-w-3xl">
      <Link to="/work-orders" className="inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft aria-hidden className="size-4" /> Work orders
      </Link>
      <h1 className="mt-3 text-2xl font-semibold tracking-tight">New work order</h1>
      <p className="mt-1 text-sm text-muted-foreground">Published work orders go to the nearest verified technicians with the skills.</p>

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

        <Field label="Required skills" name="skills" error={errors.skills} hint="Comma-separated, like cctv, networking">
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

function Field(props: { label: string; name: string; error?: string; hint?: string; children: ReactNode }) {
  return (
    <div>
      <label htmlFor={props.name} className="block text-sm font-medium">{props.label}</label>
      <div className="mt-1.5">{props.children}</div>
      {props.error ? (
        <p id={`${props.name}-error`} className="mt-1.5 text-sm text-destructive dark:text-red-400">{props.error}</p>
      ) : (
        props.hint && <p className="mt-1.5 text-sm text-muted-foreground">{props.hint}</p>
      )}
    </div>
  );
}
