// A menu entry's icon (UI-AD-08): the mapped Heroicons component, the fallback icon with the raw
// name for a name the console does not know, or nothing when the entry has no icon.
import { FallbackIcon, ICON_MAP } from './iconMap';

const ICON_CLASS = 'h-5 w-5 shrink-0';

export function MenuIcon({ name }: { name: string | null }) {
  if (name === null) return null;
  const Icon = Object.hasOwn(ICON_MAP, name) ? ICON_MAP[name] : undefined;
  if (Icon) return <Icon aria-hidden="true" data-icon={name} className={ICON_CLASS} />;
  return (
    <span className="inline-flex items-center gap-1">
      <FallbackIcon aria-hidden="true" data-icon="QuestionMarkCircleIcon" className={ICON_CLASS} />
      <span className="font-mono text-xs">{name}</span>
    </span>
  );
}
