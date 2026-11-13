package com.college.printqueue;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface PrintJobRepository extends JpaRepository<PrintJob, Long> {
 List<PrintJob> findAllByOrderByIdAsc();
}