import { randomUUID } from 'node:crypto';
import {
  LedgerEntryType,
  PayoutRail,
  PayoutStatus,
  Prisma,
  type AdSession,
  type LedgerEntry,
  type PayoutRequest,
  type PrismaClient,
  type SponsoredAlert,
  type User,
} from '@prisma/client';

interface StoreState {
  users: Map<string, User>;
  sponsoredAlerts: Map<string, SponsoredAlert>;
  adSessions: Map<string, AdSession>;
  ledgerEntries: Map<string, LedgerEntry>;
  payoutRequests: Map<string, PayoutRequest>;
}

function cloneDecimal(value: Prisma.Decimal): Prisma.Decimal {
  return new Prisma.Decimal(value.toString());
}

function cloneUser(user: User): User {
  return {
    ...user,
    interests: [...user.interests],
    walletBalance: cloneDecimal(user.walletBalance),
    dailyTimeEarned: cloneDecimal(user.dailyTimeEarned),
    dailyCapResetAt: new Date(user.dailyCapResetAt.getTime()),
    createdAt: new Date(user.createdAt.getTime()),
  };
}

function cloneAlert(alert: SponsoredAlert): SponsoredAlert {
  return {
    ...alert,
    countryCodes: [...alert.countryCodes],
    payoutPerMinute: cloneDecimal(alert.payoutPerMinute),
    commissionShare: cloneDecimal(alert.commissionShare),
    budgetRemaining: cloneDecimal(alert.budgetRemaining),
    createdAt: new Date(alert.createdAt.getTime()),
  };
}

function cloneSession(session: AdSession): AdSession {
  return {
    ...session,
    creditedAmount: cloneDecimal(session.creditedAmount),
    createdAt: new Date(session.createdAt.getTime()),
  };
}

function cloneLedgerEntry(entry: LedgerEntry): LedgerEntry {
  return {
    ...entry,
    amount: cloneDecimal(entry.amount),
    createdAt: new Date(entry.createdAt.getTime()),
  };
}

function clonePayout(payout: PayoutRequest): PayoutRequest {
  return {
    ...payout,
    amount: cloneDecimal(payout.amount),
    createdAt: new Date(payout.createdAt.getTime()),
  };
}

function snapshotState(state: StoreState): StoreState {
  return {
    users: new Map([...state.users.entries()].map(([k, v]) => [k, cloneUser(v)])),
    sponsoredAlerts: new Map(
      [...state.sponsoredAlerts.entries()].map(([k, v]) => [k, cloneAlert(v)]),
    ),
    adSessions: new Map([...state.adSessions.entries()].map(([k, v]) => [k, cloneSession(v)])),
    ledgerEntries: new Map(
      [...state.ledgerEntries.entries()].map(([k, v]) => [k, cloneLedgerEntry(v)]),
    ),
    payoutRequests: new Map(
      [...state.payoutRequests.entries()].map(([k, v]) => [k, clonePayout(v)]),
    ),
  };
}

function applyDecimalMutation(
  current: Prisma.Decimal,
  mutation: unknown,
): Prisma.Decimal {
  if (mutation instanceof Prisma.Decimal) {
    return cloneDecimal(mutation);
  }
  if (typeof mutation === 'string' || typeof mutation === 'number') {
    return new Prisma.Decimal(mutation);
  }
  if (typeof mutation === 'object' && mutation !== null) {
    const ops = mutation as { increment?: Prisma.Decimal.Value; decrement?: Prisma.Decimal.Value };
    if (ops.increment !== undefined) {
      return current.add(new Prisma.Decimal(ops.increment));
    }
    if (ops.decrement !== undefined) {
      return current.sub(new Prisma.Decimal(ops.decrement));
    }
  }
  return current;
}

function applyIntMutation(current: number, mutation: unknown): number {
  if (typeof mutation === 'number') {
    return mutation;
  }
  if (typeof mutation === 'object' && mutation !== null) {
    const ops = mutation as { increment?: number; decrement?: number };
    if (ops.increment !== undefined) {
      return current + ops.increment;
    }
    if (ops.decrement !== undefined) {
      return current - ops.decrement;
    }
  }
  return current;
}

function matchesDecimalFilter(value: Prisma.Decimal, filter: unknown): boolean {
  if (filter === undefined) {
    return true;
  }
  if (filter instanceof Prisma.Decimal || typeof filter === 'string' || typeof filter === 'number') {
    return value.eq(new Prisma.Decimal(filter));
  }
  if (typeof filter === 'object' && filter !== null) {
    const ops = filter as {
      gt?: Prisma.Decimal.Value;
      gte?: Prisma.Decimal.Value;
      lt?: Prisma.Decimal.Value;
      lte?: Prisma.Decimal.Value;
    };
    if (ops.gt !== undefined && !value.gt(new Prisma.Decimal(ops.gt))) {
      return false;
    }
    if (ops.gte !== undefined && !value.gte(new Prisma.Decimal(ops.gte))) {
      return false;
    }
    if (ops.lt !== undefined && !value.lt(new Prisma.Decimal(ops.lt))) {
      return false;
    }
    if (ops.lte !== undefined && !value.lte(new Prisma.Decimal(ops.lte))) {
      return false;
    }
  }
  return true;
}

function matchesDateFilter(value: Date, filter: unknown): boolean {
  if (filter === undefined) {
    return true;
  }
  if (filter instanceof Date) {
    return value.getTime() === filter.getTime();
  }
  if (typeof filter === 'object' && filter !== null) {
    const ops = filter as { gt?: Date; gte?: Date; lt?: Date; lte?: Date };
    if (ops.gt !== undefined && !(value.getTime() > ops.gt.getTime())) {
      return false;
    }
    if (ops.gte !== undefined && !(value.getTime() >= ops.gte.getTime())) {
      return false;
    }
    if (ops.lt !== undefined && !(value.getTime() < ops.lt.getTime())) {
      return false;
    }
    if (ops.lte !== undefined && !(value.getTime() <= ops.lte.getTime())) {
      return false;
    }
  }
  return true;
}

function compareValues(a: unknown, b: unknown, direction: 'asc' | 'desc'): number {
  let result = 0;
  if (a instanceof Prisma.Decimal && b instanceof Prisma.Decimal) {
    result = a.comparedTo(b);
  } else if (a instanceof Date && b instanceof Date) {
    result = a.getTime() - b.getTime();
  } else if (typeof a === 'string' && typeof b === 'string') {
    result = a.localeCompare(b);
  } else if (typeof a === 'number' && typeof b === 'number') {
    result = a - b;
  }
  return direction === 'desc' ? -result : result;
}

export interface MockPrismaHarness {
  prisma: PrismaClient;
  state: StoreState;
  setDatabaseHealthy: (healthy: boolean) => void;
  seedUser: (overrides?: Partial<Omit<User, 'walletBalance' | 'dailyTimeEarned'>> & {
    walletBalance?: Prisma.Decimal.Value;
    dailyTimeEarned?: Prisma.Decimal.Value;
  }) => User;
  seedAlert: (
    overrides?: Partial<
      Omit<SponsoredAlert, 'payoutPerMinute' | 'commissionShare' | 'budgetRemaining'>
    > & {
      payoutPerMinute?: Prisma.Decimal.Value;
      commissionShare?: Prisma.Decimal.Value;
      budgetRemaining?: Prisma.Decimal.Value;
    },
  ) => SponsoredAlert;
  seedSession: (
    overrides: { userId: string; alertId: string } & Partial<
      Omit<AdSession, 'userId' | 'alertId' | 'creditedAmount'>
    > & {
      creditedAmount?: Prisma.Decimal.Value;
    },
  ) => AdSession;
  seedLedgerEntry: (
    overrides: { userId: string; type?: LedgerEntryType; amount?: Prisma.Decimal.Value } & Partial<
      Omit<LedgerEntry, 'userId' | 'type' | 'amount'>
    >,
  ) => LedgerEntry;
  seedPayout: (
    overrides: { userId: string; amount?: Prisma.Decimal.Value } & Partial<
      Omit<PayoutRequest, 'userId' | 'amount'>
    >,
  ) => PayoutRequest;
}

export function createMockPrisma(baseNow: () => Date = () => new Date()): MockPrismaHarness {
  let state: StoreState = {
    users: new Map(),
    sponsoredAlerts: new Map(),
    adSessions: new Map(),
    ledgerEntries: new Map(),
    payoutRequests: new Map(),
  };

  let databaseHealthy = true;
  let seqCounter = 0;

  const nextTimestamp = (): Date => {
    seqCounter += 1;
    return new Date(baseNow().getTime() + seqCounter);
  };

  const buildDelegate = (getStore: () => StoreState) => ({
    user: {
      findUnique: async (args: { where: { id?: string; phoneHash?: string } }) => {
        const store = getStore();
        if (args.where.id !== undefined) {
          const found = store.users.get(args.where.id);
          return found ? cloneUser(found) : null;
        }
        if (args.where.phoneHash !== undefined) {
          for (const user of store.users.values()) {
            if (user.phoneHash === args.where.phoneHash) {
              return cloneUser(user);
            }
          }
        }
        return null;
      },
      upsert: async (args: {
        where: { phoneHash: string };
        create: Partial<User> & { phoneHash: string };
        update: Partial<User>;
      }) => {
        const store = getStore();
        let existing: User | undefined;
        for (const candidate of store.users.values()) {
          if (candidate.phoneHash === args.where.phoneHash) {
            existing = candidate;
            break;
          }
        }
        if (existing) {
          const updated: User = {
            ...existing,
            countryCode: args.update.countryCode ?? existing.countryCode,
            locale: args.update.locale ?? existing.locale,
            currency: args.update.currency ?? existing.currency,
            interests: Array.isArray(args.update.interests)
              ? [...args.update.interests]
              : existing.interests,
          };
          store.users.set(updated.id, updated);
          return cloneUser(updated);
        }

        const created: User = {
          id: args.create.id ?? randomUUID(),
          phoneHash: args.create.phoneHash,
          countryCode: args.create.countryCode ?? 'US',
          locale: args.create.locale ?? 'en-US',
          currency: args.create.currency ?? 'USD',
          interests: Array.isArray(args.create.interests) ? [...args.create.interests] : [],
          walletBalance: new Prisma.Decimal(args.create.walletBalance ?? 0),
          dailyTimeEarned: new Prisma.Decimal(args.create.dailyTimeEarned ?? 0),
          dailyCapResetAt: args.create.dailyCapResetAt ?? nextTimestamp(),
          createdAt: args.create.createdAt ?? nextTimestamp(),
        };
        store.users.set(created.id, created);
        return cloneUser(created);
      },
      update: async (args: { where: { id: string }; data: Record<string, unknown> }) => {
        const store = getStore();
        const existing = store.users.get(args.where.id);
        if (!existing) {
          throw new Prisma.PrismaClientKnownRequestError('Record not found', {
            code: 'P2025',
            clientVersion: '6.19.3',
          });
        }
        const updated: User = {
          ...existing,
          countryCode:
            typeof args.data['countryCode'] === 'string'
              ? args.data['countryCode']
              : existing.countryCode,
          locale:
            typeof args.data['locale'] === 'string' ? args.data['locale'] : existing.locale,
          currency:
            typeof args.data['currency'] === 'string' ? args.data['currency'] : existing.currency,
          interests: Array.isArray(args.data['interests'])
            ? [...(args.data['interests'] as string[])]
            : existing.interests,
          walletBalance:
            args.data['walletBalance'] !== undefined
              ? applyDecimalMutation(existing.walletBalance, args.data['walletBalance'])
              : existing.walletBalance,
          dailyTimeEarned:
            args.data['dailyTimeEarned'] !== undefined
              ? applyDecimalMutation(existing.dailyTimeEarned, args.data['dailyTimeEarned'])
              : existing.dailyTimeEarned,
          dailyCapResetAt:
            args.data['dailyCapResetAt'] instanceof Date
              ? args.data['dailyCapResetAt']
              : existing.dailyCapResetAt,
        };
        store.users.set(updated.id, updated);
        return cloneUser(updated);
      },
      updateMany: async (args: {
        where: {
          id?: string;
          walletBalance?: unknown;
          dailyTimeEarned?: unknown;
          dailyCapResetAt?: unknown;
        };
        data: Record<string, unknown>;
      }) => {
        const store = getStore();
        let count = 0;
        for (const existing of store.users.values()) {
          if (args.where.id !== undefined && existing.id !== args.where.id) {
            continue;
          }
          if (!matchesDecimalFilter(existing.walletBalance, args.where.walletBalance)) {
            continue;
          }
          if (!matchesDecimalFilter(existing.dailyTimeEarned, args.where.dailyTimeEarned)) {
            continue;
          }
          if (!matchesDateFilter(existing.dailyCapResetAt, args.where.dailyCapResetAt)) {
            continue;
          }
          const updated: User = {
            ...existing,
            walletBalance:
              args.data['walletBalance'] !== undefined
                ? applyDecimalMutation(existing.walletBalance, args.data['walletBalance'])
                : existing.walletBalance,
            dailyTimeEarned:
              args.data['dailyTimeEarned'] !== undefined
                ? applyDecimalMutation(existing.dailyTimeEarned, args.data['dailyTimeEarned'])
                : existing.dailyTimeEarned,
            dailyCapResetAt:
              args.data['dailyCapResetAt'] instanceof Date
                ? args.data['dailyCapResetAt']
                : existing.dailyCapResetAt,
          };
          store.users.set(updated.id, updated);
          count += 1;
        }
        return { count };
      },
    },
    sponsoredAlert: {
      findUnique: async (args: { where: { id: string } }) => {
        const found = getStore().sponsoredAlerts.get(args.where.id);
        return found ? cloneAlert(found) : null;
      },
      findMany: async (args: {
        where?: {
          active?: boolean;
          budgetRemaining?: unknown;
          countryCodes?: { has?: string };
          category?: string;
        };
        orderBy?: Array<Record<string, 'asc' | 'desc'>>;
        take?: number;
      }) => {
        const store = getStore();
        let rows = [...store.sponsoredAlerts.values()].filter((alert) => {
          if (args.where?.active !== undefined && alert.active !== args.where.active) {
            return false;
          }
          if (!matchesDecimalFilter(alert.budgetRemaining, args.where?.budgetRemaining)) {
            return false;
          }
          if (
            args.where?.countryCodes?.has !== undefined &&
            !alert.countryCodes.includes(args.where.countryCodes.has)
          ) {
            return false;
          }
          if (args.where?.category !== undefined && alert.category !== args.where.category) {
            return false;
          }
          return true;
        });

        if (args.orderBy !== undefined) {
          rows = rows.sort((a, b) => {
            for (const clause of args.orderBy ?? []) {
              for (const [key, dir] of Object.entries(clause)) {
                const cmp = compareValues(
                  a[key as keyof SponsoredAlert],
                  b[key as keyof SponsoredAlert],
                  dir,
                );
                if (cmp !== 0) {
                  return cmp;
                }
              }
            }
            return 0;
          });
        }

        if (args.take !== undefined) {
          rows = rows.slice(0, args.take);
        }

        return rows.map(cloneAlert);
      },
      update: async (args: { where: { id: string }; data: Record<string, unknown> }) => {
        const store = getStore();
        const existing = store.sponsoredAlerts.get(args.where.id);
        if (!existing) {
          throw new Prisma.PrismaClientKnownRequestError('Record not found', {
            code: 'P2025',
            clientVersion: '6.19.3',
          });
        }
        const updated: SponsoredAlert = {
          ...existing,
          active:
            typeof args.data['active'] === 'boolean' ? args.data['active'] : existing.active,
          budgetRemaining:
            args.data['budgetRemaining'] !== undefined
              ? applyDecimalMutation(existing.budgetRemaining, args.data['budgetRemaining'])
              : existing.budgetRemaining,
        };
        store.sponsoredAlerts.set(updated.id, updated);
        return cloneAlert(updated);
      },
      updateMany: async (args: {
        where: { id?: string; active?: boolean; budgetRemaining?: unknown };
        data: Record<string, unknown>;
      }) => {
        const store = getStore();
        let count = 0;
        for (const existing of store.sponsoredAlerts.values()) {
          if (args.where.id !== undefined && existing.id !== args.where.id) {
            continue;
          }
          if (args.where.active !== undefined && existing.active !== args.where.active) {
            continue;
          }
          if (!matchesDecimalFilter(existing.budgetRemaining, args.where.budgetRemaining)) {
            continue;
          }
          const updated: SponsoredAlert = {
            ...existing,
            active:
              typeof args.data['active'] === 'boolean' ? args.data['active'] : existing.active,
            budgetRemaining:
              args.data['budgetRemaining'] !== undefined
                ? applyDecimalMutation(existing.budgetRemaining, args.data['budgetRemaining'])
                : existing.budgetRemaining,
          };
          store.sponsoredAlerts.set(updated.id, updated);
          count += 1;
        }
        return { count };
      },
    },
    adSession: {
      create: async (args: {
        data: { userId: string; alertId: string; clickId?: string };
      }) => {
        const store = getStore();
        const created: AdSession = {
          id: randomUUID(),
          userId: args.data.userId,
          alertId: args.data.alertId,
          clickId: args.data.clickId ?? randomUUID(),
          activeSeconds: 0,
          creditedAmount: new Prisma.Decimal(0),
          converted: false,
          createdAt: nextTimestamp(),
        };
        store.adSessions.set(created.id, created);
        return cloneSession(created);
      },
      findUnique: async (args: {
        where: { id?: string; clickId?: string };
        include?: { user?: boolean; alert?: boolean };
      }) => {
        const store = getStore();
        let found: AdSession | undefined;
        if (args.where.id !== undefined) {
          found = store.adSessions.get(args.where.id);
        } else if (args.where.clickId !== undefined) {
          for (const candidate of store.adSessions.values()) {
            if (candidate.clickId === args.where.clickId) {
              found = candidate;
              break;
            }
          }
        }
        if (!found) {
          return null;
        }
        const base = cloneSession(found);
        const result: Record<string, unknown> = { ...base };
        if (args.include?.user) {
          const user = store.users.get(found.userId);
          if (!user) {
            return null;
          }
          result['user'] = cloneUser(user);
        }
        if (args.include?.alert) {
          const alert = store.sponsoredAlerts.get(found.alertId);
          if (!alert) {
            return null;
          }
          result['alert'] = cloneAlert(alert);
        }
        return result;
      },
      update: async (args: { where: { id: string }; data: Record<string, unknown> }) => {
        const store = getStore();
        const existing = store.adSessions.get(args.where.id);
        if (!existing) {
          throw new Prisma.PrismaClientKnownRequestError('Record not found', {
            code: 'P2025',
            clientVersion: '6.19.3',
          });
        }
        const updated: AdSession = {
          ...existing,
          activeSeconds:
            args.data['activeSeconds'] !== undefined
              ? applyIntMutation(existing.activeSeconds, args.data['activeSeconds'])
              : existing.activeSeconds,
          creditedAmount:
            args.data['creditedAmount'] !== undefined
              ? applyDecimalMutation(existing.creditedAmount, args.data['creditedAmount'])
              : existing.creditedAmount,
          converted:
            typeof args.data['converted'] === 'boolean'
              ? args.data['converted']
              : existing.converted,
        };
        store.adSessions.set(updated.id, updated);
        return cloneSession(updated);
      },
      updateMany: async (args: {
        where: { id?: string; converted?: boolean };
        data: Record<string, unknown>;
      }) => {
        const store = getStore();
        let count = 0;
        for (const existing of store.adSessions.values()) {
          if (args.where.id !== undefined && existing.id !== args.where.id) {
            continue;
          }
          if (args.where.converted !== undefined && existing.converted !== args.where.converted) {
            continue;
          }
          const updated: AdSession = {
            ...existing,
            converted:
              typeof args.data['converted'] === 'boolean'
                ? args.data['converted']
                : existing.converted,
          };
          store.adSessions.set(updated.id, updated);
          count += 1;
        }
        return { count };
      },
    },
    ledgerEntry: {
      create: async (args: {
        data: {
          userId: string;
          type: LedgerEntryType;
          amount: Prisma.Decimal.Value;
          currency: string;
          referenceId?: string | null;
        };
      }) => {
        const store = getStore();
        const created: LedgerEntry = {
          id: randomUUID(),
          userId: args.data.userId,
          type: args.data.type,
          amount: new Prisma.Decimal(args.data.amount),
          currency: args.data.currency,
          referenceId: args.data.referenceId ?? null,
          createdAt: nextTimestamp(),
        };
        store.ledgerEntries.set(created.id, created);
        return cloneLedgerEntry(created);
      },
      findMany: async (args: {
        where?: { userId?: string };
        orderBy?: Array<Record<string, 'asc' | 'desc'>>;
        cursor?: { id: string };
        skip?: number;
        take?: number;
      }) => {
        const store = getStore();
        let rows = [...store.ledgerEntries.values()].filter((entry) => {
          if (args.where?.userId !== undefined && entry.userId !== args.where.userId) {
            return false;
          }
          return true;
        });

        if (args.orderBy !== undefined) {
          rows = rows.sort((a, b) => {
            for (const clause of args.orderBy ?? []) {
              for (const [key, dir] of Object.entries(clause)) {
                const cmp = compareValues(
                  a[key as keyof LedgerEntry],
                  b[key as keyof LedgerEntry],
                  dir,
                );
                if (cmp !== 0) {
                  return cmp;
                }
              }
            }
            return 0;
          });
        }

        if (args.cursor !== undefined) {
          const cursorIndex = rows.findIndex((row) => row.id === args.cursor?.id);
          if (cursorIndex === -1) {
            return [];
          }
          rows = rows.slice(cursorIndex + (args.skip ?? 0));
        } else if (args.skip !== undefined) {
          rows = rows.slice(args.skip);
        }

        if (args.take !== undefined) {
          rows = rows.slice(0, args.take);
        }

        return rows.map(cloneLedgerEntry);
      },
    },
    payoutRequest: {
      create: async (args: {
        data: {
          userId: string;
          rail: PayoutRail;
          destination: string;
          amount: Prisma.Decimal.Value;
          currency: string;
          status?: PayoutStatus;
          externalTxId?: string | null;
        };
      }) => {
        const store = getStore();
        const created: PayoutRequest = {
          id: randomUUID(),
          userId: args.data.userId,
          rail: args.data.rail,
          destination: args.data.destination,
          amount: new Prisma.Decimal(args.data.amount),
          currency: args.data.currency,
          status: args.data.status ?? PayoutStatus.PENDING,
          externalTxId: args.data.externalTxId ?? null,
          createdAt: nextTimestamp(),
        };
        store.payoutRequests.set(created.id, created);
        return clonePayout(created);
      },
      findMany: async (args: {
        where?: { userId?: string; status?: PayoutStatus };
        orderBy?: Array<Record<string, 'asc' | 'desc'>>;
        take?: number;
      }) => {
        const store = getStore();
        let rows = [...store.payoutRequests.values()].filter((payout) => {
          if (args.where?.userId !== undefined && payout.userId !== args.where.userId) {
            return false;
          }
          if (args.where?.status !== undefined && payout.status !== args.where.status) {
            return false;
          }
          return true;
        });

        if (args.orderBy !== undefined) {
          rows = rows.sort((a, b) => {
            for (const clause of args.orderBy ?? []) {
              for (const [key, dir] of Object.entries(clause)) {
                const cmp = compareValues(
                  a[key as keyof PayoutRequest],
                  b[key as keyof PayoutRequest],
                  dir,
                );
                if (cmp !== 0) {
                  return cmp;
                }
              }
            }
            return 0;
          });
        }

        if (args.take !== undefined) {
          rows = rows.slice(0, args.take);
        }

        return rows.map(clonePayout);
      },
    },
  });

  const rootDelegates = buildDelegate(() => state);

  const mockClient = {
    ...rootDelegates,
    $queryRaw: async () => {
      if (!databaseHealthy) {
        throw new Error('Database unreachable');
      }
      return [{ '?column?': 1 }];
    },
    $disconnect: async () => undefined,
    $transaction: async <T>(fn: (tx: PrismaClient) => Promise<T>): Promise<T> => {
      const txState = snapshotState(state);
      const txClient = buildDelegate(() => txState) as unknown as PrismaClient;
      const result = await fn(txClient);
      state = txState;
      return result;
    },
  } as unknown as PrismaClient;

  return {
    prisma: mockClient,
    get state() {
      return state;
    },
    setDatabaseHealthy: (healthy: boolean) => {
      databaseHealthy = healthy;
    },
    seedUser: (overrides = {}) => {
      const user: User = {
        id: overrides.id ?? randomUUID(),
        phoneHash: overrides.phoneHash ?? `hash-${randomUUID()}`,
        countryCode: overrides.countryCode ?? 'BR',
        locale: overrides.locale ?? 'pt-BR',
        currency: overrides.currency ?? 'BRL',
        interests: overrides.interests ? [...overrides.interests] : ['food_delivery', 'ecommerce'],
        walletBalance: new Prisma.Decimal(overrides.walletBalance ?? '0.000000'),
        dailyTimeEarned: new Prisma.Decimal(overrides.dailyTimeEarned ?? '0.000000'),
        dailyCapResetAt:
          overrides.dailyCapResetAt ?? new Date(baseNow().getTime() + 24 * 60 * 60 * 1000),
        createdAt: overrides.createdAt ?? nextTimestamp(),
      };
      state.users.set(user.id, user);
      return cloneUser(user);
    },
    seedAlert: (overrides = {}) => {
      const alert: SponsoredAlert = {
        id: overrides.id ?? randomUUID(),
        advertiserName: overrides.advertiserName ?? 'iFood Brasil',
        title: overrides.title ?? 'R$ 25 Off no iFood',
        body: overrides.body ?? 'Ganhe por minuto visualizado.',
        category: overrides.category ?? 'food_delivery',
        countryCodes: overrides.countryCodes ? [...overrides.countryCodes] : ['BR'],
        priorityColor: overrides.priorityColor ?? '#10B981',
        payoutPerMinute: new Prisma.Decimal(overrides.payoutPerMinute ?? '0.180000'),
        affiliateUrl:
          overrides.affiliateUrl ??
          'https://track.yaytif.com/c/ifood-br?click_id={click_id}&market=BR',
        commissionShare: new Prisma.Decimal(overrides.commissionShare ?? '0.5000'),
        budgetRemaining: new Prisma.Decimal(overrides.budgetRemaining ?? '100.000000'),
        active: overrides.active ?? true,
        createdAt: overrides.createdAt ?? nextTimestamp(),
      };
      state.sponsoredAlerts.set(alert.id, alert);
      return cloneAlert(alert);
    },
    seedSession: (overrides) => {
      const session: AdSession = {
        id: overrides.id ?? randomUUID(),
        userId: overrides.userId,
        alertId: overrides.alertId,
        clickId: overrides.clickId ?? randomUUID(),
        activeSeconds: overrides.activeSeconds ?? 0,
        creditedAmount: new Prisma.Decimal(overrides.creditedAmount ?? '0.000000'),
        converted: overrides.converted ?? false,
        createdAt: overrides.createdAt ?? nextTimestamp(),
      };
      state.adSessions.set(session.id, session);
      return cloneSession(session);
    },
    seedLedgerEntry: (overrides) => {
      const entry: LedgerEntry = {
        id: overrides.id ?? randomUUID(),
        userId: overrides.userId,
        type: overrides.type ?? LedgerEntryType.ATTENTION_TIME,
        amount: new Prisma.Decimal(overrides.amount ?? '1.000000'),
        currency: overrides.currency ?? 'BRL',
        referenceId: overrides.referenceId ?? null,
        createdAt: overrides.createdAt ?? nextTimestamp(),
      };
      state.ledgerEntries.set(entry.id, entry);
      return cloneLedgerEntry(entry);
    },
    seedPayout: (overrides) => {
      const payout: PayoutRequest = {
        id: overrides.id ?? randomUUID(),
        userId: overrides.userId,
        rail: overrides.rail ?? PayoutRail.PIX,
        destination: overrides.destination ?? 'user@pix.br',
        amount: new Prisma.Decimal(overrides.amount ?? '5.000000'),
        currency: overrides.currency ?? 'BRL',
        status: overrides.status ?? PayoutStatus.PENDING,
        externalTxId: overrides.externalTxId ?? null,
        createdAt: overrides.createdAt ?? nextTimestamp(),
      };
      state.payoutRequests.set(payout.id, payout);
      return clonePayout(payout);
    },
  };
}
