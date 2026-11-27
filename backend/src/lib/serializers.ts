import type { LedgerEntry, PayoutRequest, SponsoredAlert, User } from '@prisma/client';
import { formatDecimal4, formatDecimal6 } from './decimal.js';

export interface SerializedUserProfile {
  id: string;
  countryCode: string;
  locale: string;
  currency: string;
  interests: string[];
  walletBalance: string;
  dailyTimeEarned: string;
  dailyCapResetAt: string;
  createdAt: string;
}

export interface SerializedSponsoredAlert {
  id: string;
  advertiserName: string;
  title: string;
  body: string;
  category: string;
  countryCodes: string[];
  priorityColor: string;
  payoutPerMinute: string;
  commissionShare: string;
  budgetRemaining: string;
  active: boolean;
  createdAt: string;
}

export interface SerializedLedgerEntry {
  id: string;
  userId: string;
  type: LedgerEntry['type'];
  amount: string;
  currency: string;
  referenceId: string | null;
  createdAt: string;
}

export interface SerializedPayoutRequest {
  id: string;
  userId: string;
  rail: PayoutRequest['rail'];
  destination: string;
  amount: string;
  currency: string;
  status: PayoutRequest['status'];
  externalTxId: string | null;
  createdAt: string;
}

export function serializeUserProfile(user: User): SerializedUserProfile {
  return {
    id: user.id,
    countryCode: user.countryCode,
    locale: user.locale,
    currency: user.currency,
    interests: user.interests,
    walletBalance: formatDecimal6(user.walletBalance),
    dailyTimeEarned: formatDecimal6(user.dailyTimeEarned),
    dailyCapResetAt: user.dailyCapResetAt.toISOString(),
    createdAt: user.createdAt.toISOString(),
  };
}

export function serializeSponsoredAlert(alert: SponsoredAlert): SerializedSponsoredAlert {
  return {
    id: alert.id,
    advertiserName: alert.advertiserName,
    title: alert.title,
    body: alert.body,
    category: alert.category,
    countryCodes: alert.countryCodes,
    priorityColor: alert.priorityColor,
    payoutPerMinute: formatDecimal6(alert.payoutPerMinute),
    commissionShare: formatDecimal4(alert.commissionShare),
    budgetRemaining: formatDecimal6(alert.budgetRemaining),
    active: alert.active,
    createdAt: alert.createdAt.toISOString(),
  };
}

export function serializeLedgerEntry(entry: LedgerEntry): SerializedLedgerEntry {
  return {
    id: entry.id,
    userId: entry.userId,
    type: entry.type,
    amount: formatDecimal6(entry.amount),
    currency: entry.currency,
    referenceId: entry.referenceId,
    createdAt: entry.createdAt.toISOString(),
  };
}

export function serializePayoutRequest(payout: PayoutRequest): SerializedPayoutRequest {
  return {
    id: payout.id,
    userId: payout.userId,
    rail: payout.rail,
    destination: payout.destination,
    amount: formatDecimal6(payout.amount),
    currency: payout.currency,
    status: payout.status,
    externalTxId: payout.externalTxId,
    createdAt: payout.createdAt.toISOString(),
  };
}
