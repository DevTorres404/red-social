// One ID per browser tab lifetime; a reload gets a fresh lease and the old one expires.
export const presenceSessionId = crypto.randomUUID();
