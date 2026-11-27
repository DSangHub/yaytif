import { Prisma } from '@prisma/client';

export const DECIMAL_ZERO = new Prisma.Decimal(0);
export const SECONDS_PER_MINUTE = new Prisma.Decimal(60);

export function toDecimal(value: Prisma.Decimal.Value): Prisma.Decimal {
  return value instanceof Prisma.Decimal ? value : new Prisma.Decimal(value);
}

export function roundMoney(value: Prisma.Decimal): Prisma.Decimal {
  return value.toDecimalPlaces(6, Prisma.Decimal.ROUND_DOWN);
}

export function formatDecimal6(value: Prisma.Decimal.Value): string {
  return toDecimal(value).toFixed(6);
}

export function formatDecimal4(value: Prisma.Decimal.Value): string {
  return toDecimal(value).toFixed(4);
}

export function calculateAttentionPayout(
  payoutPerMinute: Prisma.Decimal.Value,
  activeSecondsDelta: number,
  remainingDailyCap: Prisma.Decimal,
  remainingAlertBudget: Prisma.Decimal,
): Prisma.Decimal {
  if (activeSecondsDelta <= 0 || remainingDailyCap.lte(0) || remainingAlertBudget.lte(0)) {
    return DECIMAL_ZERO;
  }

  const rawPayout = roundMoney(
    toDecimal(payoutPerMinute).mul(activeSecondsDelta).div(SECONDS_PER_MINUTE),
  );

  const bounded = Prisma.Decimal.min(rawPayout, remainingDailyCap, remainingAlertBudget);
  return bounded.gt(0) ? roundMoney(bounded) : DECIMAL_ZERO;
}

export function calculateAffiliateCashback(
  commissionAmount: Prisma.Decimal.Value,
  commissionShare: Prisma.Decimal.Value,
): Prisma.Decimal {
  const commission = toDecimal(commissionAmount);
  const share = toDecimal(commissionShare);
  if (commission.lte(0) || share.lte(0)) {
    return DECIMAL_ZERO;
  }
  return roundMoney(commission.mul(share));
}
