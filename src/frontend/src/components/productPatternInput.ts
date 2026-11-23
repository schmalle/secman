// Product suggestions may contain commas or quotes. Quote them as one CSV field.
export function quoteProduct(value: string): string {
  return /[",]/.test(value) ? '"' + value.replaceAll('"', '""') + '"' : value;
}

export function productFields(input: string) {
  const fields: { start: number; end: number; value: string; valid: boolean }[] = [];
  let start = 0;
  let quoted = false;
  for (let pos = 0; pos <= input.length; pos++) {
    if (input[pos] === '"') quoted = !quoted;
    if (pos !== input.length && (quoted || input[pos] !== ',')) continue;
    const raw = input.slice(start, pos).trim();
    const wrapped = /^"(?:[^"]|"")*"$/.test(raw);
    const value = wrapped ? raw.slice(1, -1).replaceAll('""', '"') : raw;
    fields.push({ start, end: pos, value, valid: wrapped || !raw.includes('"') });
    start = pos + 1;
  }
  return fields;
}

export function productListError(input: string): string | null {
  const fields = productFields(input);
  if (fields.length > 100) return 'Enter up to 100 product patterns.';
  for (const [index, field] of fields.entries()) {
    if (!field.valid || !field.value.trim() || field.value.length > 512 || /^\*+$/.test(field.value.trim())) {
      return `Check product ${index + 1}. Enter 1–512 characters, including at least one literal character. Quote names containing commas.`;
    }
  }
  return null;
}
