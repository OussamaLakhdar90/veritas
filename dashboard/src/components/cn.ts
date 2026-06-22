import { clsx, type ClassValue } from 'clsx';

/** Tiny className combiner. */
export const cn = (...args: ClassValue[]) => clsx(...args);
