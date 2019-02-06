package org.wikidata.query.rdf.blazegraph;

import java.util.Collection;

import org.wikidata.query.rdf.blazegraph.inline.literal.WikibaseDateExtension;

import com.bigdata.rdf.internal.DefaultExtensionFactory;
import com.bigdata.rdf.internal.IDatatypeURIResolver;
import com.bigdata.rdf.internal.IExtension;
import com.bigdata.rdf.internal.ILexiconConfiguration;
import com.bigdata.rdf.internal.impl.extensions.DateTimeExtension;
import com.bigdata.rdf.model.BigdataLiteral;
import com.bigdata.rdf.model.BigdataValue;

/**
 * Setup inline value extensions to Blazegraph for Wikidata.
 */
public class WikibaseExtensionFactory extends DefaultExtensionFactory {
    @Override
    @SuppressWarnings("rawtypes")
    protected void _init(IDatatypeURIResolver resolver, ILexiconConfiguration<BigdataValue> config,
            Collection<IExtension<? extends BigdataValue>> extensions) {
        if (config.isInlineDateTimes()) {
            extensions.removeIf(iExtension -> iExtension instanceof DateTimeExtension);
            extensions.add(new WikibaseDateExtension<BigdataLiteral>(resolver));
        }
    }
}
