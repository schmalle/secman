package com.secman.service

import com.secman.repository.RequirementRepository
import com.secman.repository.RiskAssessmentRepository
import jakarta.inject.Inject
import jakarta.inject.Singleton

@Singleton
class TagService(
    @Inject private val requirementRepository: RequirementRepository,
    @Inject private val riskAssessmentRepository: RiskAssessmentRepository
) {

    /**
     * Gets all available tags from various sources in the system.
     * This is a simplified implementation that extracts tags from existing data.
     */
    fun getAllTags(): List<String> {
        val tags = mutableSetOf<String>()

        // Extract tags from requirements (norms, usecases, etc.)
        val requirements = requirementRepository.findCurrentRequirements()
        requirements.forEach { requirement ->
            // Add norm names as tags
            requirement.norms.forEach { norm ->
                norm.name?.let { tags.add(it) }
            }

            // Add usecase names as tags
            requirement.usecases.forEach { usecase ->
                usecase.name?.let { tags.add(it) }
            }

            // Add language as tag
            requirement.language?.let { tags.add("Language: $it") }

            // Add chapter as tag if available
            requirement.chapter?.let {
                if (it.isNotBlank()) tags.add("Chapter: $it")
            }
        }

        // Extract tags from risk assessments
        val assessments = riskAssessmentRepository.findAll()
        assessments.forEach { assessment ->
            // Add status as tag
            tags.add("Status: ${assessment.status}")

            // Add basis type as tag
            tags.add("Basis: ${assessment.assessmentBasisType.name}")

            // Add usecase names as tags
            assessment.useCases.forEach { usecase ->
                usecase.name?.let { tags.add(it) }
            }
        }

        return tags.toList().sorted()
    }

    /**
     * Gets tags filtered by category/prefix
     */
    fun getTagsByCategory(category: String): List<String> {
        return getAllTags().filter { tag ->
            tag.startsWith("$category:", ignoreCase = true)
        }
    }

    /**
     * Searches for tags matching a query
     */
    fun searchTags(query: String): List<String> {
        return getAllTags().filter { tag ->
            tag.contains(query, ignoreCase = true)
        }
    }

    /**
     * Gets the most commonly used tags
     */
    fun getTopTags(limit: Int = 10): List<String> {
        val tagCounts = mutableMapOf<String, Int>()

        // Count tag usage in requirements
        val requirements = requirementRepository.findCurrentRequirements()
        requirements.forEach { requirement ->
            requirement.norms.forEach { norm ->
                norm.name?.let { tag ->
                    tagCounts[tag] = tagCounts.getOrDefault(tag, 0) + 1
                }
            }
            requirement.usecases.forEach { usecase ->
                usecase.name?.let { tag ->
                    tagCounts[tag] = tagCounts.getOrDefault(tag, 0) + 1
                }
            }
        }

        // Return top tags by usage count
        return tagCounts.toList()
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * Gets all categories (prefixes) of tags
     */
    fun getTagCategories(): List<String> {
        return getAllTags()
            .mapNotNull { tag ->
                if (tag.contains(":")) {
                    tag.substring(0, tag.indexOf(":"))
                } else null
            }
            .distinct()
            .sorted()
    }
}