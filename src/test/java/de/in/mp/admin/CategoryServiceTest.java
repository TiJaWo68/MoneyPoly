package de.in.mp.admin;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.in.mp.finance.Category;
import de.in.mp.finance.CategoryRepository;
import de.in.mp.finance.ClassificationRuleRepository;

class CategoryServiceTest {

    private CategoryRepository categoryRepository;
    private ClassificationRuleRepository classificationRuleRepository;
    private CategoryService service;

    @BeforeEach
    void setUp() {
        categoryRepository = mock(CategoryRepository.class);
        classificationRuleRepository = mock(ClassificationRuleRepository.class);
        service = new CategoryService(categoryRepository, classificationRuleRepository);
    }

    @Test
    void saveRejectsBlankName() {
        Category category = category(null, "   ");

        assertThatThrownBy(() -> service.save(category))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Name");
    }

    @Test
    void saveRejectsDuplicateName() {
        Category existing = category(UUID.randomUUID(), "Lebensmittel");
        when(categoryRepository.findByName("Lebensmittel")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.save(category(null, "Lebensmittel")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void deleteRejectsCategoryUsedByAllocations() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(category(id, "Lebensmittel")));
        when(categoryRepository.countTransactionAllocations(id)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in use");

        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void deleteRejectsCategoryUsedByRule() {
        UUID id = UUID.randomUUID();
        when(categoryRepository.findById(id)).thenReturn(Optional.of(category(id, "Lebensmittel")));
        when(classificationRuleRepository.countByTargetCategoryId(id)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(id))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("in use");

        verify(categoryRepository, never()).delete(any(Category.class));
    }

    @Test
    void deleteSucceedsForUnusedCategory() {
        UUID id = UUID.randomUUID();
        Category category = category(id, "Lebensmittel");
        when(categoryRepository.findById(id)).thenReturn(Optional.of(category));

        service.delete(id);

        verify(categoryRepository).delete(category);
    }

    private Category category(UUID id, String name) {
        Category category = new Category();
        category.setId(id);
        category.setName(name);
        return category;
    }
}
