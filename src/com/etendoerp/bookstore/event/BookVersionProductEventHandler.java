package com.etendoerp.bookstore.event;

import com.etendoerp.bookstore.data.BookVersion;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.criterion.Restrictions;
import org.openbravo.base.model.Entity;
import org.openbravo.base.model.ModelProvider;
import org.openbravo.base.model.Property;
import org.openbravo.base.provider.OBProvider;
import org.openbravo.client.kernel.event.EntityNewEvent;
import org.openbravo.client.kernel.event.EntityPersistenceEventObserver;
import org.openbravo.dal.service.OBDal;
import org.openbravo.model.common.plm.Product;
import org.openbravo.model.common.plm.ProductCategory;
import org.openbravo.model.common.uom.UOM;
import org.openbravo.model.financialmgmt.tax.TaxCategory;

import javax.enterprise.event.Observes;
import java.util.UUID;

public class BookVersionProductEventHandler extends EntityPersistenceEventObserver {

    private static final Logger logger = LogManager.getLogger();
    private static final String BOOK_CATEGORY_SEARCHKEY = "Book";
    private static final String DEFAULT_UOM_NAME = "Unit";
    private static final String DEFAULT_TAX_NAME = "Exempt";

    private static final Entity[] entities = {
            ModelProvider.getInstance().getEntity(BookVersion.ENTITY_NAME)
    };

    @Override
    protected Entity[] getObservedEntities() {
        return entities;
    }

    public void onSave(@Observes EntityNewEvent event) {
        if (!isValidEvent(event)) return;

        final BookVersion bv = (BookVersion) event.getTargetInstance();

        if (bv.getProduct() != null) {
            logger.debug("BookVersion {} already has an associated product, skipping creation.", bv.getName());
            return;
        }

        logger.info("Creating product for BookVersion {}", bv.getName());

        String uuid = UUID.randomUUID().toString().replace("-", "");
        Product p = OBProvider.getInstance().get(Product.class);
        p.setId(uuid);
        p.setNewOBObject(true);

        p.setClient(bv.getClient());
        p.setOrganization(bv.getOrganization());
        p.setActive(true);
        p.setName(bv.getName());
        p.setSearchKey(bv.getSearchKey());
        p.setStocked(false);
        p.setProduction(false);

        ProductCategory cat = findCategory(BOOK_CATEGORY_SEARCHKEY);
        if (cat == null) {
            throw new IllegalStateException("Book category not found.");
        }
        p.setProductCategory(cat);

        UOM uom = findUOM(DEFAULT_UOM_NAME);
        if (uom == null) {
            throw new IllegalStateException("UOM '" + DEFAULT_UOM_NAME + "' not found.");
        }
        p.setUOM(uom);

        TaxCategory taxCat = (TaxCategory) OBDal.getInstance()
                .createCriteria(TaxCategory.class)
                .add(Restrictions.eq(TaxCategory.PROPERTY_NAME, DEFAULT_TAX_NAME))
                .setMaxResults(1)
                .uniqueResult();
        if (taxCat == null) {
            throw new IllegalStateException("TaxCategory '" + DEFAULT_TAX_NAME + "' not found.");
        }
        p.setTaxCategory(taxCat);

        OBDal.getInstance().save(p);

        final Entity bvEntity = ModelProvider.getInstance().getEntity(BookVersion.ENTITY_NAME);
        final Property bvProductProperty = bvEntity.getProperty(BookVersion.PROPERTY_PRODUCT);
        event.setCurrentState(bvProductProperty, p);

        logger.info("Product {} created and associated with BookVersion {}", p.getSearchKey(), bv.getName());
    }

    private UOM findUOM(String name) {
        UOM u = (UOM) OBDal.getInstance()
                .createCriteria(UOM.class)
                .add(Restrictions.eq(UOM.PROPERTY_NAME, name))
                .setMaxResults(1)
                .uniqueResult();
        if (u != null) return u;
        return OBDal.getInstance().get(UOM.class, "100");
    }

    private ProductCategory findCategory(String searchKey) {
        return (ProductCategory) OBDal.getInstance()
                .createCriteria(ProductCategory.class)
                .add(Restrictions.eq(ProductCategory.PROPERTY_SEARCHKEY, searchKey))
                .setMaxResults(1)
                .uniqueResult();
    }

}
