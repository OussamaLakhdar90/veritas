import React from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { api } from '../api';
import { Input } from './ui';

/**
 * A service text input backed by a datalist of services the platform already holds work for — so a user picks from
 * existing services (and sees their pipeline counts) instead of guessing the exact string. Still accepts a new name.
 */
export function ServiceField(props: React.InputHTMLAttributes<HTMLInputElement>) {
  const { t } = useTranslation();
  const q = useQuery({ queryKey: ['services'], queryFn: api.services });
  const services = q.data ?? [];
  return (
    <>
      <Input list="veritas-services" placeholder={t('serviceField.placeholder')} autoComplete="off" {...props} />
      <datalist id="veritas-services">
        {services.map((s) => (
          <option key={s.name} value={s.name} label={t('serviceField.optionLabel', { cases: s.cases, conditions: s.conditions, scans: s.scans })} />
        ))}
      </datalist>
    </>
  );
}
