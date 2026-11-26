import 'dotenv/config';
import { createHash } from 'node:crypto';
import { LedgerEntryType, Prisma, PrismaClient } from '@prisma/client';
import { z } from 'zod';

const prisma = new PrismaClient();

const decimalStringSchema = z
  .string()
  .regex(/^\d+(\.\d{1,6})?$/, 'Must be a non-negative decimal string with up to 6 fractional digits');

const seedCampaignSchema = z.object({
  id: z.string().uuid(),
  advertiserName: z.string().min(1),
  title: z.string().min(1),
  body: z.string().min(1),
  category: z.string().min(1),
  countryCodes: z.array(z.string().length(2)).min(1),
  priorityColor: z.string().regex(/^#[0-9A-Fa-f]{6}$/),
  payoutPerMinute: decimalStringSchema,
  affiliateUrl: z
    .string()
    .url()
    .refine((url) => url.includes('{click_id}'), {
      message: 'affiliateUrl must include the {click_id} tracking macro',
    }),
  commissionShare: decimalStringSchema,
  budgetRemaining: decimalStringSchema,
  active: z.boolean(),
});

const seedUserSchema = z.object({
  id: z.string().uuid(),
  syntheticPhone: z.string().min(6),
  countryCode: z.string().length(2),
  locale: z.string().min(2).max(16),
  currency: z.string().length(3),
  interests: z.array(z.string().min(1)).min(1),
  walletBalance: decimalStringSchema,
  dailyTimeEarned: decimalStringSchema,
  initialLedgerEntryId: z.string().uuid(),
});

type SeedCampaign = z.infer<typeof seedCampaignSchema>;
type SeedUser = z.infer<typeof seedUserSchema>;

function hashPhone(phone: string): string {
  return createHash('sha256').update(`yaytif-seed:${phone}`).digest('hex');
}

const SAMPLE_CAMPAIGNS: readonly SeedCampaign[] = [
  {
    id: '11111111-1111-4111-8111-111111111101',
    advertiserName: 'iFood Brasil',
    title: 'R$ 25 Off no iFood + Cashback via Pix',
    body: 'Ganhe por minuto visualizado e receba 50% da comissão de afiliado direto no Pix.',
    category: 'food_delivery',
    countryCodes: ['BR'],
    priorityColor: '#10B981',
    payoutPerMinute: '0.180000',
    affiliateUrl: 'https://track.yaytif.com/c/ifood-br?click_id={click_id}&market=BR&rail=PIX',
    commissionShare: '0.5000',
    budgetRemaining: '25000.000000',
    active: true,
  },
  {
    id: '11111111-1111-4111-8111-111111111102',
    advertiserName: 'Mercado Livre Brasil',
    title: 'Frete Grátis Mercado Livre + Bônus em BRL',
    body: 'Ofertas relâmpago em eletrônicos com crédito verificado por tempo de atenção.',
    category: 'ecommerce',
    countryCodes: ['BR'],
    priorityColor: '#F59E0B',
    payoutPerMinute: '0.220000',
    affiliateUrl: 'https://track.yaytif.com/c/mercadolivre-br?click_id={click_id}&market=BR&rail=PIX',
    commissionShare: '0.5000',
    budgetRemaining: '40000.000000',
    active: true,
  },
  {
    id: '22222222-2222-4222-8222-222222222201',
    advertiserName: 'Jumia Nigeria',
    title: 'Jumia & Tecno Flash Drop — Earn NGN to OPay',
    body: 'Earn verified Naira per minute viewed plus 50% affiliate cashback settled via Paystack/OPay.',
    category: 'ecommerce',
    countryCodes: ['NG'],
    priorityColor: '#10B981',
    payoutPerMinute: '45.000000',
    affiliateUrl: 'https://track.yaytif.com/c/jumia-ng?click_id={click_id}&market=NG&rail=PAYSTACK_OPAY',
    commissionShare: '0.5000',
    budgetRemaining: '8500000.000000',
    active: true,
  },
  {
    id: '22222222-2222-4222-8222-222222222202',
    advertiserName: 'OPay Nigeria',
    title: 'OPay Zero-Fee Transfers + Instant Airtime Bonus',
    body: 'Explore OPay savings and instant transfer perks while earning NGN attention rewards.',
    category: 'fintech',
    countryCodes: ['NG'],
    priorityColor: '#059669',
    payoutPerMinute: '60.000000',
    affiliateUrl: 'https://track.yaytif.com/c/opay-ng?click_id={click_id}&market=NG&rail=PAYSTACK_OPAY',
    commissionShare: '0.5000',
    budgetRemaining: '12000000.000000',
    active: true,
  },
  {
    id: '33333333-3333-4333-8333-333333333301',
    advertiserName: 'Jumia Kenya',
    title: 'Ofa ya Jumia & Tecno — Pata Cashback ya M-Pesa',
    body: 'Lipwa KSh kwa kila dakika unayotazama na gawio la 50% moja kwa moja kupitia M-Pesa.',
    category: 'ecommerce',
    countryCodes: ['KE'],
    priorityColor: '#10B981',
    payoutPerMinute: '4.500000',
    affiliateUrl: 'https://track.yaytif.com/c/jumia-ke?click_id={click_id}&market=KE&rail=MPESA',
    commissionShare: '0.5000',
    budgetRemaining: '950000.000000',
    active: true,
  },
  {
    id: '33333333-3333-4333-8333-333333333302',
    advertiserName: 'Safaricom Bundles',
    title: 'Bonasi ya Data na M-Pesa — Malipo Papo Hapo',
    body: 'Pata ofa za vifurushi vya intaneti na zawadi za muda wa umakini kwa M-Pesa.',
    category: 'telecom',
    countryCodes: ['KE', 'TZ'],
    priorityColor: '#16A34A',
    payoutPerMinute: '5.250000',
    affiliateUrl: 'https://track.yaytif.com/c/safaricom-ke?click_id={click_id}&market=KE&rail=MPESA',
    commissionShare: '0.5000',
    budgetRemaining: '1250000.000000',
    active: true,
  },
  {
    id: '44444444-4444-4444-8444-444444444401',
    advertiserName: 'Amazon Deals US',
    title: 'Daily Lightning Deals — Earn USD to PayPal',
    body: 'Earn verified USD per minute viewed plus a 50% affiliate commission share to PayPal.',
    category: 'ecommerce',
    countryCodes: ['US'],
    priorityColor: '#10B981',
    payoutPerMinute: '0.050000',
    affiliateUrl: 'https://track.yaytif.com/c/amazon-us?click_id={click_id}&market=US&rail=PAYPAL',
    commissionShare: '0.5000',
    budgetRemaining: '15000.000000',
    active: true,
  },
  {
    id: '44444444-4444-4444-8444-444444444402',
    advertiserName: 'Wise Global Transfers',
    title: 'Low-Fee International Transfers + PayPal Reward',
    body: 'Discover transparent FX rates for cross-border transfers and earn attention rewards.',
    category: 'fintech',
    countryCodes: ['US', 'BR', 'NG', 'KE'],
    priorityColor: '#0EA5E9',
    payoutPerMinute: '0.080000',
    affiliateUrl: 'https://track.yaytif.com/c/wise-global?click_id={click_id}&market=US&rail=PAYPAL',
    commissionShare: '0.5000',
    budgetRemaining: '20000.000000',
    active: true,
  },
];

const SAMPLE_USERS: readonly SeedUser[] = [
  {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa01',
    syntheticPhone: '+5511999990001',
    countryCode: 'BR',
    locale: 'pt-BR',
    currency: 'BRL',
    interests: ['food_delivery', 'ecommerce', 'fintech'],
    walletBalance: '14.500000',
    dailyTimeEarned: '2.100000',
    initialLedgerEntryId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb01',
  },
  {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa02',
    syntheticPhone: '+2348010000002',
    countryCode: 'NG',
    locale: 'en-NG',
    currency: 'NGN',
    interests: ['fintech', 'ecommerce', 'telecom'],
    walletBalance: '4250.000000',
    dailyTimeEarned: '630.000000',
    initialLedgerEntryId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb02',
  },
  {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa03',
    syntheticPhone: '+254700000003',
    countryCode: 'KE',
    locale: 'sw-KE',
    currency: 'KES',
    interests: ['telecom', 'ecommerce', 'fintech'],
    walletBalance: '380.000000',
    dailyTimeEarned: '54.000000',
    initialLedgerEntryId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb03',
  },
  {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaa04',
    syntheticPhone: '+14155550004',
    countryCode: 'US',
    locale: 'en-US',
    currency: 'USD',
    interests: ['ecommerce', 'fintech'],
    walletBalance: '6.250000',
    dailyTimeEarned: '0.850000',
    initialLedgerEntryId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbb04',
  },
];

async function seed(): Promise<void> {
  const validatedCampaigns = SAMPLE_CAMPAIGNS.map((campaign) => seedCampaignSchema.parse(campaign));
  const validatedUsers = SAMPLE_USERS.map((user) => seedUserSchema.parse(user));

  const dailyCapResetAt = new Date();
  dailyCapResetAt.setUTCHours(24, 0, 0, 0);

  await prisma.$transaction(async (tx) => {
    for (const campaign of validatedCampaigns) {
      await tx.sponsoredAlert.upsert({
        where: { id: campaign.id },
        update: {
          advertiserName: campaign.advertiserName,
          title: campaign.title,
          body: campaign.body,
          category: campaign.category,
          countryCodes: campaign.countryCodes,
          priorityColor: campaign.priorityColor,
          payoutPerMinute: new Prisma.Decimal(campaign.payoutPerMinute),
          affiliateUrl: campaign.affiliateUrl,
          commissionShare: new Prisma.Decimal(campaign.commissionShare),
          budgetRemaining: new Prisma.Decimal(campaign.budgetRemaining),
          active: campaign.active,
        },
        create: {
          id: campaign.id,
          advertiserName: campaign.advertiserName,
          title: campaign.title,
          body: campaign.body,
          category: campaign.category,
          countryCodes: campaign.countryCodes,
          priorityColor: campaign.priorityColor,
          payoutPerMinute: new Prisma.Decimal(campaign.payoutPerMinute),
          affiliateUrl: campaign.affiliateUrl,
          commissionShare: new Prisma.Decimal(campaign.commissionShare),
          budgetRemaining: new Prisma.Decimal(campaign.budgetRemaining),
          active: campaign.active,
        },
      });
    }

    for (const user of validatedUsers) {
      const phoneHash = hashPhone(user.syntheticPhone);
      const walletBalance = new Prisma.Decimal(user.walletBalance);
      const dailyTimeEarned = new Prisma.Decimal(user.dailyTimeEarned);

      const seededUser = await tx.user.upsert({
        where: { phoneHash },
        update: {
          countryCode: user.countryCode,
          locale: user.locale,
          currency: user.currency,
          interests: user.interests,
        },
        create: {
          id: user.id,
          phoneHash,
          countryCode: user.countryCode,
          locale: user.locale,
          currency: user.currency,
          interests: user.interests,
          walletBalance,
          dailyTimeEarned,
          dailyCapResetAt,
        },
      });

      await tx.ledgerEntry.upsert({
        where: { id: user.initialLedgerEntryId },
        update: {},
        create: {
          id: user.initialLedgerEntryId,
          userId: seededUser.id,
          type: LedgerEntryType.ATTENTION_TIME,
          amount: walletBalance,
          currency: user.currency,
          referenceId: `seed-opening-balance:${user.countryCode}`,
        },
      });
    }
  });

  console.info(
    `Seeded ${validatedCampaigns.length} localized SponsoredAlert campaigns and ${validatedUsers.length} regional User profiles (BR, NG, KE, US).`,
  );
}

seed()
  .catch((error: unknown) => {
    console.error('Failed to seed Yaytif database:', error);
    process.exitCode = 1;
  })
  .finally(async () => {
    await prisma.$disconnect();
  });
