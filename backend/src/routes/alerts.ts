import type { Prisma } from '@prisma/client';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { DECIMAL_ZERO } from '../lib/decimal.js';
import { notFound } from '../lib/errors.js';
import { serializeSponsoredAlert } from '../lib/serializers.js';
import { countryCodeSchema, parseInput, uuidSchema } from '../lib/validation.js';
import type { RouteContext } from './context.js';

const listAlertsQuerySchema = z
  .object({
    countryCode: countryCodeSchema.optional(),
    userId: uuidSchema.optional(),
    category: z.string().trim().min(1).max(64).optional(),
    limit: z.coerce.number().int().min(1).max(100).optional().default(50),
  })
  .strict()
  .refine((data) => data.countryCode !== undefined || data.userId !== undefined, {
    message: 'Either countryCode or userId must be provided',
    path: ['countryCode'],
  });

export function registerAlertRoutes(app: FastifyInstance, ctx: RouteContext): void {
  app.get('/alerts', async (request, reply) => {
    const query = parseInput(listAlertsQuerySchema, request.query);

    let userInterests: string[] = [];
    let resolvedCountryCode = query.countryCode;

    if (query.userId !== undefined) {
      const user = await ctx.prisma.user.findUnique({
        where: { id: query.userId },
        select: {
          id: true,
          countryCode: true,
          interests: true,
        },
      });

      if (!user) {
        throw notFound('User not found', 'USER_NOT_FOUND');
      }

      userInterests = user.interests;
      resolvedCountryCode ??= user.countryCode;
    }

    const effectiveCountryCode = resolvedCountryCode as string;

    const where: Prisma.SponsoredAlertWhereInput = {
      active: true,
      budgetRemaining: { gt: DECIMAL_ZERO },
      countryCodes: { has: effectiveCountryCode },
      ...(query.category !== undefined ? { category: query.category } : {}),
    };

    const alerts = await ctx.prisma.sponsoredAlert.findMany({
      where,
      orderBy: [{ payoutPerMinute: 'desc' }, { createdAt: 'desc' }],
      take: query.limit,
    });

    const orderedAlerts =
      userInterests.length > 0 && query.category === undefined
        ? (() => {
            const interestSet = new Set(userInterests);
            const matched = alerts.filter((alert) => interestSet.has(alert.category));
            const others = alerts.filter((alert) => !interestSet.has(alert.category));
            return [...matched, ...others];
          })()
        : alerts;

    return reply.code(200).send({
      countryCode: effectiveCountryCode,
      alerts: orderedAlerts.map(serializeSponsoredAlert),
      count: orderedAlerts.length,
    });
  });
}
