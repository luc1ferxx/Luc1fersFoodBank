package com.laioffer.onlineorder.aftersales;


import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.ListCrudRepository;


import java.util.Collection;
import java.util.List;


public interface AfterSalesCaseLineRepository extends ListCrudRepository<AfterSalesCaseLineEntity, Long> {

    /**
     * The lines of several cases, by case and then in their order; {@code caseIds} must not be empty.
     */
    @Query("""
            SELECT *
            FROM after_sales_case_lines
            WHERE case_id IN (:caseIds)
            ORDER BY case_id, id
            """)
    List<AfterSalesCaseLineEntity> findAllByCaseIdIn(Collection<Long> caseIds);
}
