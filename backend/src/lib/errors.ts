import { Prisma } from '@prisma/client';
import type { FastifyError, FastifyReply, FastifyRequest } from 'fastify';
import { ZodError } from 'zod';

export class AppError extends Error {
  public readonly statusCode: number;
  public readonly code: string;
  public readonly details?: Record<string, unknown>;

  constructor(
    statusCode: number,
    code: string,
    message: string,
    details?: Record<string, unknown>,
  ) {
    super(message);
    this.name = 'AppError';
    this.statusCode = statusCode;
    this.code = code;
    if (details !== undefined) {
      this.details = details;
    }
  }
}

export function badRequest(
  message: string,
  code = 'BAD_REQUEST',
  details?: Record<string, unknown>,
): AppError {
  return new AppError(400, code, message, details);
}

export function unauthorized(message: string, code = 'UNAUTHORIZED'): AppError {
  return new AppError(401, code, message);
}

export function notFound(message: string, code = 'NOT_FOUND'): AppError {
  return new AppError(404, code, message);
}

export function conflict(
  message: string,
  code = 'CONFLICT',
  details?: Record<string, unknown>,
): AppError {
  return new AppError(409, code, message, details);
}

export function registerErrorHandler(
  error: FastifyError | Error,
  request: FastifyRequest,
  reply: FastifyReply,
): void {
  if (error instanceof ZodError) {
    const issues = error.issues.map((issue) => ({
      path: issue.path.join('.'),
      code: issue.code,
      message: issue.message,
    }));
    void reply.code(400).send({
      error: {
        code: 'VALIDATION_ERROR',
        message: 'Request validation failed',
        issues,
      },
    });
    return;
  }

  if (error instanceof AppError) {
    void reply.code(error.statusCode).send({
      error: {
        code: error.code,
        message: error.message,
        ...(error.details !== undefined ? { details: error.details } : {}),
      },
    });
    return;
  }

  if (error instanceof Prisma.PrismaClientKnownRequestError) {
    if (error.code === 'P2002') {
      void reply.code(409).send({
        error: {
          code: 'UNIQUE_CONSTRAINT_VIOLATION',
          message: 'A resource with the provided unique identifier already exists',
        },
      });
      return;
    }
    if (error.code === 'P2025') {
      void reply.code(404).send({
        error: {
          code: 'NOT_FOUND',
          message: 'Requested resource was not found',
        },
      });
      return;
    }
    if (error.code === 'P2003') {
      void reply.code(400).send({
        error: {
          code: 'INVALID_REFERENCE',
          message: 'Referenced resource does not exist',
        },
      });
      return;
    }
  }

  const fastifyStatus =
    'statusCode' in error && typeof error.statusCode === 'number' ? error.statusCode : undefined;
  if (fastifyStatus !== undefined && fastifyStatus >= 400 && fastifyStatus < 500) {
    void reply.code(fastifyStatus).send({
      error: {
        code: 'code' in error && typeof error.code === 'string' ? error.code : 'REQUEST_ERROR',
        message: error.message,
      },
    });
    return;
  }

  request.log.error(
    {
      err: error,
      method: request.method,
      url: request.url,
    },
    'Unhandled backend error',
  );

  void reply.code(500).send({
    error: {
      code: 'INTERNAL_SERVER_ERROR',
      message: 'An unexpected error occurred',
    },
  });
}
