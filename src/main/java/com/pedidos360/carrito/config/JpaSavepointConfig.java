package com.pedidos360.carrito.config;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import java.sql.Savepoint;
import java.sql.SQLException;
import org.hibernate.Session;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.SavepointManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

/**
 * El checkout usa savepoints para recuperarse de una colisión de clave idempotente dentro de la
 * misma transacción. En Spring 6.1 el dialecto los expone devolviendo un SavepointManager desde
 * beginTransaction; Hibernate no lo hace, así que lo apoyamos en la conexión JDBC del EntityManager.
 */
@Configuration
public class JpaSavepointConfig {

  @Bean
  JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
    // El contenedor vuelve a llamar afterPropertiesSet(); sin este override reposición el dialecto
    // que trae el EntityManagerFactory y se pierde la capacidad de savepoints.
    return new JpaTransactionManager(entityManagerFactory) {
      @Override
      public void afterPropertiesSet() {
        super.afterPropertiesSet();
        setJpaDialect(new JdbcSavepointDialect());
      }
    };
  }

  static class JdbcSavepointDialect extends HibernateJpaDialect {
    @Override
    public Object beginTransaction(EntityManager entityManager, TransactionDefinition definition)
        throws PersistenceException, SQLException, TransactionException {
      Object original = super.beginTransaction(entityManager, definition);
      return new SavepointData(original, entityManager.unwrap(Session.class));
    }

    @Override
    public void cleanupTransaction(Object transactionData) {
      super.cleanupTransaction(transactionData instanceof SavepointData data ? data.original : transactionData);
    }
  }

  static class SavepointData implements SavepointManager {
    private final Object original;
    private final Session session;

    SavepointData(Object original, Session session) {
      this.original = original;
      this.session = session;
    }

    @Override
    public Object createSavepoint() {
      return session.doReturningWork(connection -> connection.setSavepoint());
    }

    @Override
    public void rollbackToSavepoint(Object savepoint) {
      session.doWork(connection -> connection.rollback((Savepoint) savepoint));
      // El INSERT que chocó sigue en la cola de Hibernate: sin limpiar la sesión se reintentaría
      // en el próximo flush y volvería a fallar.
      session.clear();
    }

    @Override
    public void releaseSavepoint(Object savepoint) {
      session.doWork(connection -> connection.releaseSavepoint((Savepoint) savepoint));
    }
  }
}
