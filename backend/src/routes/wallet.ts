import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { formatDecimal6 } from '../lib/decimal.js';
import { notFound } from '../lib/errors.js';
import { serializeLedgerEntry } from '../lib/serializers.js';
import { parseInput, userIdParamsSchema, uuidSchema } from '../lib/validation.js';
import type { RouteContext } from './context.js';

const walletLedgerQuerySchema = z
  .object({
    cursor: uuidSchema.optional(),
    limit: z.coerce.number().int().min(1).max(100).optional().default(20),
  })
  .strict();

export function registerWalletRoutes(app: FastifyInstance, ctx: RouteContext): void {
  app.get('/wallet/:userId/ledger', async (request, reply) => {
    const params = parseInput(userIdParamsSchema, request.params);
    const query = parseInput(walletLedgerQuerySchema, request.query);

    const user = await ctx.prisma.user.findUnique({
      where: { id: params.userId },
    });

    if (!user) {
      throw notFound('User not found', 'USER_NOT_FOUND');
    }

    const rows = await ctx.prisma.ledgerEntry.findMany({
      where: { userId: user.id },
      orderBy: [{ createdAt: 'desc' }, { id: 'desc' }],
      take: query.limit + 1,
      ...(query.cursor !== undefined ? { cursor: { id: query.cursor }, skip: 1 } : {}),
    });

    const hasMore = rows.length > query.limit;
    const entries = hasMore ? rows.slice(0, query.limit) : rows;
    const lastEntry = entries[entries.length - 1];
    const nextCursor = hasMore && lastEntry !== undefined ? lastEntry.id : null;

    return reply.code(200).send({
      wallet: {
        userId: user.id,
        walletBalance: formatDecimal6(user.walletBalance),
        dailyTimeEarned: formatDecimal6(user.dailyTimeEarned),
        dailyCapResetAt: user.dailyCapResetAt.toISOString(),
        currency: user.currency,
      },
      entries: entries.map(serializeLedgerEntry),
      pagination: {
        limit: query.limit,
        hasMore,
        nextCursor,
      },
    });
  });
}
