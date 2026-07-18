/**
 * Shared FinOps currency formatting utilities.
 *
 * Uses the native Intl.NumberFormat API to ensure locale-aware,
 * consistent USD rendering across all FinOps administrative surfaces.
 */

const currencyFormatter = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
});

const precisionFormatter = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 4,
    maximumFractionDigits: 4,
});

const rateFormatter = new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 3,
    maximumFractionDigits: 4,
});

/** Standard USD display: $12.45 */
export const formatUsd = (value: number): string => currencyFormatter.format(value);

/** High-precision USD for micro-costs: $0.0750 */
export const formatUsdPrecision = (value: number): string => precisionFormatter.format(value);

/** Rate display for per-1K token pricing: $2.500 */
export const formatUsdRate = (value: number): string => rateFormatter.format(value);
