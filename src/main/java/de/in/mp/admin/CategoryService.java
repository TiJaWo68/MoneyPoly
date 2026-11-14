package de.in.mp.admin;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import de.in.mp.finance.Category;
import de.in.mp.finance.CategoryRepository;
import de.in.mp.finance.ClassificationRuleRepository;
import de.in.mp.finance.HouseholdService;

/**
 * Administration service for booking categories.
 * <p>
 * Categories referenced by transaction allocations or classification rules
 * cannot be hard-deleted.
 */
@Service
public class CategoryService {

    private static final int NAME_MAX_LENGTH = 60;

    private final CategoryRepository categoryRepository;
    private final ClassificationRuleRepository classificationRuleRepository;
    private final HouseholdService householdService;

    public CategoryService(CategoryRepository categoryRepository,
            ClassificationRuleRepository classificationRuleRepository) {
        this(categoryRepository, classificationRuleRepository, null);
    }

    @Autowired
    public CategoryService(CategoryRepository categoryRepository,
            ClassificationRuleRepository classificationRuleRepository,
            @Autowired(required = false) HouseholdService householdService) {
        this.categoryRepository = categoryRepository;
        this.classificationRuleRepository = classificationRuleRepository;
        this.householdService = householdService;
    }

    @Transactional(readOnly = true)
    public List<Category> findAll() {
        UUID householdId = householdService == null ? null : householdService.resolveEffectiveHouseholdId(null);
        return householdId == null
                ? categoryRepository.findAllByOrderByNameAsc()
                : categoryRepository.findByHouseholdIdOrderByNameAsc(householdId);
    }

    @Transactional
    public Category save(Category category) {
        if (category != null && category.getHouseholdId() == null && householdService != null) {
            category.setHouseholdId(householdService.resolveEffectiveHouseholdId(null));
        }
        validate(category);
        return categoryRepository.save(category);
    }

    @Transactional
    public void delete(UUID id) {
        Category category = requireCategory(id);
        if (categoryRepository.countTransactionAllocations(id) > 0
                || classificationRuleRepository.countByTargetCategoryId(id) > 0) {
            throw new IllegalArgumentException("Category is in use and cannot be deleted");
        }
        categoryRepository.delete(category);
    }

    @Transactional(readOnly = true)
    public long countAllocations(UUID id) {
        return categoryRepository.countTransactionAllocations(id);
    }

    private void validate(Category category) {
        if (category == null) {
            throw new IllegalArgumentException("Category must not be null");
        }
        if (category.getName() == null || category.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required");
        }
        String name = category.getName().trim();
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Name must not exceed " + NAME_MAX_LENGTH + " characters");
        }
        category.setName(name);

        var existingByName = category.getHouseholdId() == null
                ? categoryRepository.findByName(name)
                : categoryRepository.findByHouseholdIdAndName(category.getHouseholdId(), name);
        existingByName.ifPresent(existing -> {
            if (!existing.getId().equals(category.getId())) {
                throw new IllegalArgumentException("Name already exists");
            }
        });
    }

    private Category requireCategory(UUID id) {
        return categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Category not found: " + id));
    }
}
