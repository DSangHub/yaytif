import type { Prisma } from '@prisma/client';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { notFound } from '../lib/errors.js';
import { serializeUserProfile } from '../lib/serializers.js';
import {
  countryCodeSchema,
  currencySchema,
  emptyQuerySchema,
  interestsSchema,
  localeSchema,
  parseInput,
  userIdParamsSchema,
} from '../lib/validation.js';
import type { RouteContext } from './context.js';

const DAY_MS = 24 * 60 * 60 * 1000;

const registerUserBodySchema = z
  .object({
    phoneHash: z.string().trim().min(8).max(128),
    countryCode: countryCodeSchema.optional().default('US'),
    locale: localeSchema.optional().default('en-US'),
    currency: currencySchema.optional().default('USD'),
    interests: interestsSchema.optional().default([]),
  })
  .strict();

const updateUserInterestsBodySchema = z
  .object({
    interests: interestsSchema,
    locale: localeSchema.optional(),
    countryCode: countryCodeSchema.optional(),
    currency: currencySchema.optional(),
  })
  .strict();

export function registerUserRoutes(app: FastifyInstance, ctx: RouteContext): void {
  app.post('/users/register', async (request, reply) => {
    parseInput(emptyQuerySchema, request.query);
    const body = parseInput(registerUserBodySchema, request.body);

    const initialCapResetAt = new Date(ctx.now().getTime() + DAY_MS);

    const user = await ctx.prisma.user.upsert({
      where: { phoneHash: body.phoneHash },
      create: {
        phoneHash: body.phoneHash,
        countryCode: body.countryCode,
        locale: body.locale,
        currency: body.currency,
        interests: body.interests,
        dailyCapResetAt: initialCapResetAt,
      },
      update: {
        countryCode: body.countryCode,
        locale: body.locale,
        currency: body.currency,
        interests: body.interests,
      },
    });

    return reply.code(200).send(serializeUserProfile(user));
  });

  app.get('/users/:userId', async (request, reply) => {
    const params = parseInput(userIdParamsSchema, request.params);
    parseInput(emptyQuerySchema, request.query);

    const user = await ctx.prisma.user.findUnique({
      where: { id: params.userId },
    });

    if (!user) {
      throw notFound('User not found', 'USER_NOT_FOUND');
    }

    return reply.code(200).send(serializeUserProfile(user));
  });

  app.patch('/users/:userId/interests', async (request, reply) => {
    const params = parseInput(userIdParamsSchema, request.params);
    parseInput(emptyQuerySchema, request.query);
    const body = parseInput(updateUserInterestsBodySchema, request.body);

    const existing = await ctx.prisma.user.findUnique({
      where: { id: params.userId },
      select: { id: true },
    });

    if (!existing) {
      throw notFound('User not found', 'USER_NOT_FOUND');
    }

    const updateData: Prisma.UserUpdateInput = {
      interests: body.interests,
    };
    if (body.locale !== undefined) {
      updateData.locale = body.locale;
    }
    if (body.countryCode !== undefined) {
      updateData.countryCode = body.countryCode;
    }
    if (body.currency !== undefined) {
      updateData.currency = body.currency;
    }

    const updatedUser = await ctx.prisma.user.update({
      where: { id: params.userId },
      data: updateData,
    });

    return reply.code(200).send(serializeUserProfile(updatedUser));
  });
}
