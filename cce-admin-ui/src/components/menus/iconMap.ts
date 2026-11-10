// The ten seed icon names (admin-service §6) mapped to Heroicons 2 outline components (UI-AD-08).
// A name not in the map renders the fallback icon and the raw name.
import {
  ArrowDownTrayIcon,
  BuildingOffice2Icon,
  ChartBarIcon,
  ClipboardDocumentCheckIcon,
  CogIcon,
  ExclamationTriangleIcon,
  LightBulbIcon,
  QuestionMarkCircleIcon,
  SignalIcon,
  UserGroupIcon,
  UserIcon,
} from '@heroicons/react/24/outline';

export type IconComponent = typeof ChartBarIcon;

export const ICON_MAP: Readonly<Record<string, IconComponent>> = {
  ChartBarIcon,
  BuildingOffice2Icon,
  ClipboardDocumentCheckIcon,
  ExclamationTriangleIcon,
  UserGroupIcon,
  SignalIcon,
  CogIcon,
  UserIcon,
  LightBulbIcon,
  ArrowDownTrayIcon,
};

/** The ten names, in seed order, for the icon select. */
export const ICON_NAMES: readonly string[] = Object.keys(ICON_MAP);

export const FallbackIcon: IconComponent = QuestionMarkCircleIcon;

export function isKnownIcon(name: string): boolean {
  return Object.hasOwn(ICON_MAP, name);
}
