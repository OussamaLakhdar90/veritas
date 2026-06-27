import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '../api';
import { Input } from './ui';

/**
 * A service text input backed by a datalist of services the platform already holds work for — so a user picks from
 * existing services (and sees their pipeline counts) instead of guessing the exact string. Still accepts a new name.
 */
export function ServiceField(props: React.InputHTMLAttributes<HTMLInputElement>) {
  const q = useQuery({ queryKey: ['services'], queryFn: api.services });
  const services = q.data ?? [];
  return (
    <>
      <Input list="veritas-services" placeholder="ciam-policies" autoComplete="off" {...props} />
      <datalist id="veritas-services">
        {services.map((s) => (
          <option key={s.name} value={s.name} label={`${s.cases} cases · ${s.conditions} conditions · ${s.scans} scans`} />
        ))}
      </datalist>
    </>
  );
}
