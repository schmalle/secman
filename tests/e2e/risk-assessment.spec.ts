import { test, expect, type APIRequestContext, type Page } from '@playwright/test';

const requiredEnvVars = ['SECMAN_ADMIN_NAME', 'SECMAN_ADMIN_PASS'] as const;
const missing = requiredEnvVars.filter((key) => !process.env[key]);
if (missing.length > 0) {
  throw new Error(
    `Missing required environment variables: ${missing.join(', ')}. ` +
    'Use ./run-e2e.sh which pulls them from Proton Pass.',
  );
}

const BOOTSTRAP_ADMIN = {
  user: process.env.SECMAN_ADMIN_NAME!,
  pass: process.env.SECMAN_ADMIN_PASS!,
};

const E2E_PREFIX = 'e2e-risk-assessment-';

type UserFixture = {
  id: number;
  username: string;
  email: string;
  password: string;
};

type FixtureState = {
  runId: string;
  users: {
    admin: UserFixture;
    assessor: UserFixture;
    respondent: UserFixture;
    viewer: UserFixture;
  };
  asset: { id: number; name: string };
  useCase: { id: number; name: string };
  requirements: Array<{ id: number; shortreq: string }>;
  assessment: { id: number };
};

let fixture: FixtureState;

async function expectOk(response: Awaited<ReturnType<APIRequestContext['get']>>, label: string) {
  if (!response.ok()) {
    throw new Error(`${label} failed: ${response.status()} ${await response.text()}`);
  }
}

async function loginApi(request: APIRequestContext, username: string, password: string) {
  const response = await request.post('/api/auth/login', {
    data: { username, password },
  });
  await expectOk(response, `API login for ${username}`);
  return response.json();
}

async function loginUi(page: Page, username: string, password: string) {
  await page.context().clearCookies();
  await page.goto('/login');
  await page.waitForLoadState('domcontentloaded');
  await expect(page.locator('#username')).toBeVisible();
  await page.locator('#username').fill(username);
  await page.locator('#password').fill(password);
  const loginResponse = page.waitForResponse((response) =>
    response.url().includes('/api/auth/login') &&
    response.request().method() === 'POST',
  );
  const redirected = page.waitForURL((url) => !url.pathname.endsWith('/login'), { timeout: 15_000 });
  await page.locator('button[type="submit"]').click();
  expect((await loginResponse).ok()).toBe(true);
  await redirected;
}

async function createUser(
  request: APIRequestContext,
  runId: string,
  suffix: string,
  roles: string[],
): Promise<UserFixture> {
  const username = `${E2E_PREFIX}${runId}-${suffix}`;
  const email = `${username}@e2e.test`;
  const password = `RiskE2E-${runId}-${suffix}!`;
  const response = await request.post('/api/users', {
    data: {
      username,
      email,
      password,
      roles,
      mfaEnabled: false,
    },
  });
  await expectOk(response, `create user ${username}`);
  const body = await response.json();
  return { id: body.id, username, email, password };
}

async function createUseCase(request: APIRequestContext, runId: string) {
  for (let attempt = 1; attempt <= 10; attempt += 1) {
    const name = `${E2E_PREFIX}${runId}-usecase-${attempt}`;
    const response = await request.post('/api/usecases', {
      data: { name },
    });
    if (response.ok()) {
      const body = await response.json();
      return { id: body.id, name };
    }

    const body = await response.text();
    if (!body.includes('duplicate entry violates a database constraint')) {
      throw new Error(`create risk-assessment use case failed: ${response.status()} ${body}`);
    }
  }

  throw new Error('create risk-assessment use case failed after retrying duplicate primary-key conflicts');
}

async function createFixture(request: APIRequestContext): Promise<FixtureState> {
  const runId = Date.now().toString(36);

  const users = {
    admin: await createUser(request, runId, 'admin', ['USER', 'ADMIN', 'RISK', 'REQ', 'SECCHAMPION']),
    assessor: await createUser(request, runId, 'assessor', ['USER', 'RISK', 'REQ', 'SECCHAMPION']),
    respondent: await createUser(request, runId, 'respondent', ['USER', 'RISK', 'REQ']),
    viewer: await createUser(request, runId, 'viewer', ['USER']),
  };

  await loginApi(request, users.admin.username, users.admin.password);

  const assetName = `${E2E_PREFIX}${runId}-asset`;
  const assetResponse = await request.post('/api/assets', {
    data: {
      name: assetName,
      type: 'Server',
      owner: users.assessor.username,
      ip: '10.120.0.10',
      description: `${E2E_PREFIX}${runId} test asset`,
      networkZone: 'INTERNAL',
    },
  });
  await expectOk(assetResponse, 'create risk-assessment asset');
  const assetBody = await assetResponse.json();

  const useCaseBody = await createUseCase(request, runId);

  const requirementPayloads = [
    {
      shortreq: `${E2E_PREFIX}${runId} require MFA`,
      details: 'E2E requirement: privileged access must use MFA.',
      language: 'en',
      norm: 'E2E',
      chapter: 'RA-1',
      usecaseIds: [useCaseBody.id],
    },
    {
      shortreq: `${E2E_PREFIX}${runId} require recovery evidence`,
      details: 'E2E requirement: recovery evidence must be documented.',
      language: 'en',
      norm: 'E2E',
      chapter: 'RA-2',
      usecaseIds: [useCaseBody.id],
    },
  ];

  const requirements = [];
  for (const payload of requirementPayloads) {
    const requirementResponse = await request.post('/api/requirements', { data: payload });
    await expectOk(requirementResponse, `create requirement ${payload.shortreq}`);
    requirements.push(await requirementResponse.json());
  }

  const endDate = new Date(Date.now() + 7 * 24 * 60 * 60 * 1000).toISOString().slice(0, 10);
  const assessmentResponse = await request.post('/api/risk-assessments', {
    data: {
      assetId: assetBody.id,
      assessorRef: { id: users.assessor.id },
      respondentRef: { id: users.respondent.id },
      endDate,
      notes: `${E2E_PREFIX}${runId} lifecycle coverage`,
      useCaseIds: [useCaseBody.id],
    },
  });
  await expectOk(assessmentResponse, 'create asset-backed risk assessment');
  const assessmentBody = await assessmentResponse.json();

  return {
    runId,
    users,
    asset: { id: assetBody.id, name: assetName },
    useCase: { id: useCaseBody.id, name: useCaseBody.name },
    requirements: requirements.map((requirement) => ({
      id: requirement.id,
      shortreq: requirement.shortreq,
    })),
    assessment: { id: assessmentBody.id },
  };
}

async function cleanupRiskAssessmentE2eData(request: APIRequestContext) {
  await loginApi(request, BOOTSTRAP_ADMIN.user, BOOTSTRAP_ADMIN.pass);

  const risks = await request.get('/api/risks');
  if (risks.ok()) {
    for (const risk of await risks.json()) {
      const riskName = risk.name ?? '';
      const assetName = risk.asset?.name ?? '';
      if (riskName.includes(E2E_PREFIX) || assetName.includes(E2E_PREFIX)) {
        await request.delete(`/api/risks/${risk.id}`);
      }
    }
  }

  const assessments = await request.get('/api/risk-assessments');
  if (assessments.ok()) {
    for (const assessment of await assessments.json()) {
      const notes = assessment.notes ?? '';
      const assetName = assessment.asset?.name ?? '';
      const relatedUsers = [
        assessment.assessor?.username,
        assessment.requestor?.username,
        assessment.respondent?.username,
      ].filter(Boolean);
      if (
        notes.includes(E2E_PREFIX) ||
        assetName.includes(E2E_PREFIX) ||
        relatedUsers.some((username: string) => username.startsWith(E2E_PREFIX))
      ) {
        await request.delete(`/api/risk-assessments/${assessment.id}`);
      }
    }
  }

  const assets = await request.get('/api/assets');
  if (assets.ok()) {
    for (const asset of await assets.json()) {
      if ((asset.name ?? '').includes(E2E_PREFIX)) {
        await request.delete(`/api/assets/${asset.id}`);
      }
    }
  }

  const requirements = await request.get('/api/requirements');
  if (requirements.ok()) {
    for (const requirement of await requirements.json()) {
      if ((requirement.shortreq ?? '').includes(E2E_PREFIX)) {
        await request.delete(`/api/requirements/${requirement.id}`);
      }
    }
  }

  const useCases = await request.get('/api/usecases');
  if (useCases.ok()) {
    for (const useCase of await useCases.json()) {
      if ((useCase.name ?? '').includes(E2E_PREFIX)) {
        await request.delete(`/api/usecases/${useCase.id}`);
      }
    }
  }

  const users = await request.get('/api/users');
  await expectOk(users, 'list users during cleanup');
  for (const user of await users.json()) {
    if ((user.username ?? '').startsWith(E2E_PREFIX)) {
      await request.delete(`/api/users/${user.id}`);
    }
  }

  const remainingUsers = await request.get('/api/users');
  await expectOk(remainingUsers, 'verify users after cleanup');
  const remainingE2eUsers = (await remainingUsers.json()).filter((user: { username: string }) =>
    user.username.startsWith(E2E_PREFIX),
  );
  expect(remainingE2eUsers, 'all risk-assessment E2E users must be deleted').toHaveLength(0);
}

test.describe.serial('Risk assessment end-to-end lifecycle', () => {
  test.beforeAll(async ({ request }) => {
    await cleanupRiskAssessmentE2eData(request);
    await loginApi(request, BOOTSTRAP_ADMIN.user, BOOTSTRAP_ADMIN.pass);
    fixture = await createFixture(request);
  });

  test.afterAll(async ({ request }) => {
    await cleanupRiskAssessmentE2eData(request);
  });

  test('uses only generated users and blocks non-risk users from assessment APIs', async ({ playwright }) => {
    const context = await playwright.request.newContext({
      baseURL: process.env.SECMAN_BASE_URL || process.env.SECMAN_BACKEND_URL || 'http://localhost:4321',
      ignoreHTTPSErrors: true,
    });
    try {
      await loginApi(context, fixture.users.viewer.username, fixture.users.viewer.password);
      const forbidden = await context.get('/api/risk-assessments');
      expect(forbidden.status()).toBe(403);
    } finally {
      await context.dispose();
    }
  });

  test('assessor completes an asset-backed assessment and raises a risk from a failed answer', async ({ page, request }) => {
    test.setTimeout(120_000);
    const consoleErrors: string[] = [];
    page.on('console', (message) => {
      if (message.type() === 'error') {
        const text = message.text();
        if (!text.includes('Failed to load resource: the server responded with a status of 403')) {
          consoleErrors.push(text);
        }
      }
    });

    await loginUi(page, fixture.users.assessor.username, fixture.users.assessor.password);
    await page.goto('/riskassessment');
    await page.waitForLoadState('domcontentloaded');

    const assessmentRow = page.locator('tr', { hasText: fixture.asset.name });
    await expect(assessmentRow).toContainText(fixture.users.assessor.username);
    await expect(assessmentRow).toContainText(fixture.users.respondent.username);
    await assessmentRow.getByRole('button', { name: 'Perform Assessment' }).click();

    await expect(page.getByRole('heading', { name: 'Perform Assessment' })).toBeVisible();
    await expect(page.getByText(`${fixture.requirements.length} Requirements`)).toBeVisible();

    const compliantRequirement = page.locator('.list-group-item', { hasText: fixture.requirements[0].shortreq });
    await compliantRequirement.getByText(/^Compliant$/).click();
    await compliantRequirement.getByPlaceholder('Add comments...').fill('MFA is enforced for privileged access.');

    const nonCompliantRequirement = page.locator('.list-group-item', { hasText: fixture.requirements[1].shortreq });
    await nonCompliantRequirement.getByText(/^Non-Compliant$/).click();
    await nonCompliantRequirement.getByPlaceholder('Add comments...').fill('Recovery evidence is missing.');
    await nonCompliantRequirement.getByLabel('Raise risk for this non-compliance').check();

    await page.getByRole('button', { name: /Risk Raising/ }).click();
    await page.getByPlaceholder('Describe the risk...').fill('Missing recovery evidence can delay incident restoration.');
    await page.locator('input[type="number"]').first().fill('4');
    await page.locator('input[type="number"]').nth(1).fill('5');

    const saveResponsePromise = page.waitForResponse((response) =>
      response.url().includes(`/api/responses/assessment/${fixture.assessment.id}/bulk-save`) &&
      response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Save Progress' }).click();
    const saveResponse = await saveResponsePromise;
    expect(saveResponse.ok(), `bulk-save failed: ${saveResponse.status()} ${await saveResponse.text()}`).toBe(true);
    await expect(page.getByText(/Progress saved successfully!/)).toBeVisible();
    await expect(page.getByText('100% Complete')).toBeVisible();

    await page.getByRole('button', { name: /Assessment \(/ }).click();
    await nonCompliantRequirement.getByLabel('Raise risk for this non-compliance').check();
    await page.getByRole('button', { name: /Risk Raising/ }).click();
    await page.getByPlaceholder('Describe the risk...').fill('Missing recovery evidence can delay incident restoration.');
    await page.locator('input[type="number"]').first().fill('4');
    await page.locator('input[type="number"]').nth(1).fill('5');

    const completeResponsePromise = page.waitForResponse((response) =>
      response.url().includes(`/api/risk-assessments/${fixture.assessment.id}`) &&
      response.request().method() === 'PUT',
    );
    await page.getByRole('button', { name: 'Complete Assessment' }).click();
    const completeResponse = await completeResponsePromise;
    if (!completeResponse.ok()) {
      throw new Error(`assessment completion failed: ${completeResponse.status()} ${await completeResponse.text()}`);
    }
    await expect(page.getByRole('heading', { name: 'Perform Assessment' })).toBeHidden({ timeout: 15_000 });

    await loginApi(request, fixture.users.admin.username, fixture.users.admin.password);
    await expect.poll(async () => {
      const assessmentResponse = await request.get(`/api/risk-assessments/${fixture.assessment.id}`);
      await expectOk(assessmentResponse, 'fetch completed assessment');
      const assessment = await assessmentResponse.json();
      return assessment.status;
    }, {
      message: 'risk assessment status should be persisted as completed',
      timeout: 15_000,
    }).toBe('COMPLETED');

    const responsesResponse = await request.get(`/api/responses/assessment/${fixture.assessment.id}`);
    await expectOk(responsesResponse, 'fetch completed assessment responses');
    const responses = await responsesResponse.json();
    expect(responses).toHaveLength(fixture.requirements.length);
    expect(responses.map((response: { answerType: string }) => response.answerType).sort()).toEqual(['NO', 'YES']);

    await expect.poll(async () => {
      const risksResponse = await request.get(`/api/risks/asset/${fixture.asset.id}`);
      await expectOk(risksResponse, 'fetch risk raised from assessment');
      const risks = await risksResponse.json();
      return risks.some((risk: { description?: string }) =>
        risk.description?.includes('Missing recovery evidence can delay incident restoration.'),
      );
    }, {
      message: 'risk should be raised for the non-compliant assessment answer',
      timeout: 15_000,
    }).toBe(true);

    expect(consoleErrors, `JS errors observed: ${consoleErrors.join('\n')}`).toHaveLength(0);
  });
});
