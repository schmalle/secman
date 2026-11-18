/** Start independent assessment reads together while treating suggestions as optional. */
export async function loadAssessmentSections<Assessment, Requirements, Suggestions>(
  getAssessment: () => Promise<Assessment>,
  getRequirements: () => Promise<Requirements>,
  getSuggestions: () => Promise<Suggestions>,
  onSuggestionsError: (error: unknown) => void,
): Promise<{ assessment: Assessment; requirements: Requirements; suggestions: Suggestions | null }> {
  const [assessment, requirements, suggestions] = await Promise.all([
    getAssessment(),
    getRequirements(),
    getSuggestions().catch(error => {
      onSuggestionsError(error);
      return null;
    }),
  ]);
  return { assessment, requirements, suggestions };
}
