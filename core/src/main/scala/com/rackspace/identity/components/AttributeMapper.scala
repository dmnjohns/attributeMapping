/**
 *   Copyright 2016 Rackspace US, Inc.
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package com.rackspace.identity.components

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File, OutputStream, Writer}
import java.net.URI
import javax.xml.parsers.DocumentBuilder
import javax.xml.transform.Source
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.stream.StreamSource
import javax.xml.validation.{Schema, SchemaFactory}

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper, SerializationFeature}
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.rackspace.cloud.api.wadl.util.LogErrorListener
import com.rackspace.com.papi.components.checker.util.XMLParserPool
import net.sf.saxon.Configuration.LicenseFeature._
import net.sf.saxon.s9api._
import net.sf.saxon.serialize.MessageWarner
import org.w3c.dom.Document

object XSDEngine extends Enumeration {
  val AUTO = Value("auto")
  val SAXON = Value("saxon")
  val XERCES = Value("xerces")
}

object PolicyFormat extends Enumeration {
  val XML = Value("xml")
  val JSON = Value("json")
  val YAML = Value("yaml")
}

import com.rackspace.identity.components.XSDEngine._
import com.rackspace.identity.components.PolicyFormat._

object AttributeMapper {

  //
  // The version of XQuery (and, by extension, XPath) to use.
  //
  final val XQUERY_VERSION = 31
  final val XQUERY_VERSION_STRING = "3.1"

  //
  // The namespace components for the "mapping" namespace.
  //
  final val MAPPING_NS_PREFIX = "mapping"
  final val MAPPING_NS_URI = "http://docs.rackspace.com/identity/api/ext/MappingRules"

  val processor = {
    val p = new Processor(true)
    val dynLoader = p.getUnderlyingConfiguration.getDynamicLoader
    dynLoader.setClassLoader(getClass.getClassLoader)
    p
  }
  private val internalProcessor = {
    val p = new Processor(processor.getUnderlyingConfiguration)
    p.registerExtensionFunction(new ValidateXPathFunction(p.getUnderlyingConfiguration))
    p
  }

  val compiler = processor.newXsltCompiler
  val xqueryCompiler = {
    val c = processor.newXQueryCompiler
    c.setLanguageVersion(XQUERY_VERSION_STRING)
    c
  }
  private val internalXPathCompiler = {
    val c = internalProcessor.newXPathCompiler()
    c.setLanguageVersion(XQUERY_VERSION_STRING)
    c.declareNamespace(MAPPING_NS_PREFIX, MAPPING_NS_URI)
    c
  }

  private val mapperXsltExec = compiler.compile(new StreamSource(getClass.getResource("/xsl/mapping.xsl").toString))
  private lazy val mapper2JSONExec = xqueryCompiler.compile(getClass.getResourceAsStream("/xq/mapping2JSON.xq"))
  private lazy val mapper2XMLExec = xqueryCompiler.compile(getClass.getResourceAsStream("/xq/mapping2XML.xq"))
  private lazy val validateXPathExec = internalXPathCompiler.compile(
    """
      |for $path in //mapping:remote/mapping:attribute[@path]/@path
      |return mapping:validate-xpath(/mapping:mapping, $path)
    """.stripMargin
  )

  private lazy val mappingXSDSource = new StreamSource(getClass.getResource("/xsd/mapping.xsd").toString)

  private lazy val extractExtExec = compiler.compile(new StreamSource(getClass.getResource("/xsl/extract-ext.xsl").toString))
  private lazy val joinExtExec = compiler.compile(new StreamSource(getClass.getResource("/xsl/join-ext.xsl").toString))
  private lazy val ext2JSONExec = xqueryCompiler.compile(getClass.getResourceAsStream("/xq/ext2JSON.xq"))

  private lazy val extAttribsXSDSource = new StreamSource(getClass.getResource("/xsd/extAttribs.xsd").toString)

  private val transformerFactory = new net.sf.saxon.TransformerFactoryImpl
  private def idTransform = {
    val idt = transformerFactory.newTransformer()
    idt.setErrorListener (new LogErrorListener)
    idt
  }

  private lazy val schemaFactory = {
    val sf = SchemaFactory.newInstance("http://www.w3.org/XML/XMLSchema/v1.1", "org.apache.xerces.jaxp.validation.XMLSchema11Factory",
                              this.getClass.getClassLoader)
    //
    //  Enable CTA full XPath2.0 checking in XSD 1.1
    //
    sf.setFeature ("http://apache.org/xml/features/validation/cta-full-xpath-checking", true)
    sf
  }

  private def getSchemaManager(xsdSource : Source) : SchemaManager = {
    val sm = processor.getSchemaManager

    sm.setXsdVersion("1.1")
    sm.setErrorListener (new LogErrorListener)
    sm.load(xsdSource)
    sm
  }

  //
  //  Xerces Schemas for validation
  //
  private lazy val mappingSchema = schemaFactory.newSchema(mappingXSDSource)
  private lazy val extAttribsSchema = schemaFactory.newSchema(extAttribsXSDSource)

  //
  //  Saxon Schemas for validation
  //
  private lazy val mappingSchemaManager = getSchemaManager(mappingXSDSource)
  private lazy val extAttribsSchemaManager = getSchemaManager(extAttribsXSDSource)

  //
  //  Given XSLTExec and an optional set of XSLT parameters, creates an XsltTransformer
  //
  def getXsltTransformer (xsltExec : XsltExecutable, params : Map[QName, XdmValue]=Map[QName, XdmValue]()) : XsltTransformer = {
    val t = xsltExec.load
    t.setErrorListener (new LogErrorListener)
    t.getUnderlyingController.setMessageEmitter(new MessageWarner)
    for {(param, value) <- params} {
      t.setParameter(param, value)
    }
    t
  }

  def getXQueryEvaluator (xqueryExec : XQueryExecutable, params : Map[QName, XdmValue]=Map[QName, XdmValue]()) : XQueryEvaluator = {
    val e = xqueryExec.load
    e.setErrorListener (new LogErrorListener)
    for {(param, value) <- params} {
      e.setExternalVariable (param, value)
    }
    e
  }

  def useSaxon(engineStr : String) : Boolean = {
    val engine = XSDEngine.withName(engineStr)
    ((engine == AUTO &&
      processor.getUnderlyingConfiguration.isLicensedFeature(SCHEMA_VALIDATION))
     || engine == SAXON)
  }

  def validate(src : Source, engineStr : String, schema : => Schema, schemaManager : => SchemaManager) : Source = {
    val docBuilder = processor.newDocumentBuilder
    val bch = docBuilder.newBuildingContentHandler

    if (useSaxon(engineStr)) {
      Console.err.println("Saxon validation") // scalastyle:ignore
      val svalidator = schemaManager.newSchemaValidator
      svalidator.setDestination(new SAXDestination(bch))
      svalidator.validate(src)
    } else {
      Console.err.println("Xerces validation") // scalastyle:ignore
      val schemaHandler = schema.newValidatorHandler
      schemaHandler.setContentHandler(bch)
      idTransform.transform(src, new SAXResult(schemaHandler))
    }
    bch.getDocumentNode.asSource
  }

  def validatePolicy (policy : Source, engineStr : String) : Source = {
    val docBuilder = processor.newDocumentBuilder
    val xdmPolicy = docBuilder.build(policy)

    //
    // Pre-parse the source to verify that XPath expressions are acceptable.
    //
    val xpathSelector = validateXPathExec.load()
    xpathSelector.setContextItem(xdmPolicy)
    xpathSelector.evaluate()

    validate(xdmPolicy.asSource, engineStr, mappingSchema, mappingSchemaManager)
  }

  def validatePolicy (policy : JsonNode, engineStr : String) : JsonNode = {
    val outXMLPolicy = new XdmDestination

    policy2XML(policy, outXMLPolicy)
    val valXMLPolicySrc = validatePolicy(outXMLPolicy.getXdmNode.asSource, engineStr)

    val bout = new ByteArrayOutputStream
    val dest = processor.newSerializer(bout)

    policy2JSON(valXMLPolicySrc, dest, false, engineStr)

    val bin = new ByteArrayInputStream(bout.toByteArray)
    parseJsonNode(new StreamSource(bin))
  }

  def generateXSL (policy : Source, xsl : Destination, isJSON : Boolean, validate : Boolean, xsdEngine : String) : Unit = {
    val policySourceConv1 = {
      if (isJSON) {
        val outPolicyXML = new XdmDestination
        policy2XML(policy.asInstanceOf[StreamSource], outPolicyXML)
        outPolicyXML.getXdmNode.asSource
      } else {
        policy
      }
    }

    val policySrc = {
      if (validate) {
        validatePolicy(policySourceConv1, xsdEngine)
      } else {
        policySourceConv1
      }
    }

    val mappingTrans = getXsltTransformer(mapperXsltExec)
    mappingTrans.setSource(policySrc)
    mappingTrans.setDestination(xsl)
    mappingTrans.transform()
  }

  def generateXSL (policy : JsonNode, xsl : Destination, validate : Boolean, xsdEngine : String) : Unit = {
    val outPolicyXML = new XdmDestination
    policy2XML(policy, outPolicyXML)

    generateXSL(outPolicyXML.getXdmNode.asSource, xsl, false, validate, xsdEngine)
  }

  def generateXSLExec (policy : Source, isJSON : Boolean, validate : Boolean, xsdEngine : String) : XsltExecutable = {
    val outXSL = new XdmDestination

    generateXSL (policy, outXSL, isJSON, validate, xsdEngine)
    compiler.compile(outXSL.getXdmNode.asSource)
  }

  def generateXSLExec (policy : JsonNode, validate : Boolean, xsdEngine : String) : XsltExecutable = {
    val outXSL = new XdmDestination

    generateXSL (policy, outXSL, validate, xsdEngine)
    compiler.compile(outXSL.getXdmNode.asSource)
  }

  def generateXSLExec (policy : Document, validate : Boolean, xsdEngine : String) : XsltExecutable = {
    generateXSLExec (new DOMSource(policy), false, validate, xsdEngine)
  }

  def parseJsonNode (source : StreamSource) : JsonNode = {
    val om = new ObjectMapper()

    if (source.getInputStream != null) {
      om.readTree(source.getInputStream)
    } else if (source.getReader != null) {
      om.readTree(source.getReader)
    } else {
      om.readTree(new File(new URI(source.getSystemId)))
    }
  }

  def parseYamlNode (source : StreamSource) : JsonNode = {
    val om = new ObjectMapper(new YAMLFactory())

    if (source.getInputStream != null) {
      om.readTree(source.getInputStream)
    } else if (source.getReader != null) {
      om.readTree(source.getReader)
    } else {
      om.readTree(new File(new URI(source.getSystemId)))
    }
  }

  def policy2JSON(policyXML : Source, policyJSON : Destination, validate : Boolean, xsdEngine : String) : Unit = {
    val policySrc = {
      if (validate) {
        validatePolicy(policyXML, xsdEngine)
      } else {
        policyXML
      }
    }

    val evaluator = getXQueryEvaluator(mapper2JSONExec)
    evaluator.setSource(policySrc)
    evaluator.setDestination(policyJSON)
    evaluator.run()
  }

  def policy2XML(policyJSON : StreamSource, policyXML : Destination) : Unit = {
    policy2XML(parseJsonNode(policyJSON), policyXML)
  }

  def policy2XML(node : JsonNode, policyXML : Destination) : Unit = {
    val om = new ObjectMapper()

    val evaluator = getXQueryEvaluator(mapper2XMLExec, Map[QName, XdmValue](new QName("__JSON__") -> new XdmAtomicValue(om.writeValueAsString(node))))
    evaluator.setDestination(policyXML)
    evaluator.run()
  }

  // todo: Is it alright if the YAML support change is not backwards compatible?
  def convertAssertion (policy : Source, assertion : Source, dest : Destination, outputSAML : Boolean,
                        policyFormat : PolicyFormat.Value, validate : Boolean, xsdEngine : String) : Unit = {
    // todo: replace this shim with full YAML support in other methods
    //
    // Generate the XSLTExec
    //
    val mapExec = policyFormat match {
      case XML =>
        generateXSLExec (policy, false, validate, xsdEngine)
      case JSON =>
        generateXSLExec (policy, true, validate, xsdEngine)
      case YAML =>
        generateXSLExec (parseYamlNode(new StreamSource(policy.getSystemId)), validate, xsdEngine)
    }

    //
    //  Run the generate XSL on the assertion
    //
    convertAssertion(mapExec, assertion, dest, outputSAML, !XML.equals(policyFormat))
  }

  def convertAssertion (policyExec : XsltExecutable, assertion : Source, dest : Destination, outputSAML : Boolean, toJSON : Boolean) : Unit = {
    val assertionDest = {
      if (toJSON && !outputSAML) {
        new XdmDestination
      } else {
        dest
      }
    }

    //
    //  Run the generate XSL on the assertion
    //
    val mapTrans = getXsltTransformer (policyExec, Map(new QName("outputSAML") -> new XdmAtomicValue(outputSAML)))
    mapTrans.setSource(assertion)
    mapTrans.setDestination(assertionDest)
    mapTrans.transform()

    if (toJSON && !outputSAML) {
      policy2JSON(assertionDest.asInstanceOf[XdmDestination].getXdmNode.asSource, dest, false, "Xerces")
    }
  }

  def convertAssertion (policyExec : XsltExecutable, assertion : Source) : Document = {
    var docBuilder : DocumentBuilder = null
    var outDoc : Document = null
    try {
      docBuilder = XMLParserPool.borrowParser
      outDoc = docBuilder.newDocument
    } finally {
      if (docBuilder != null) XMLParserPool.returnParser(docBuilder)
    }

    val dest = new DOMDestination(outDoc)
    convertAssertion (policyExec, assertion, dest, true, false)
    outDoc
  }

  def convertAssertion (policyExec : XsltExecutable, assertion : Document) : Document = {
    convertAssertion (policyExec, new DOMSource(assertion))
  }

  def validateExtAttributes (extAttribs : Source, engineStr : String) : Source = {
    validate (extAttribs, engineStr, extAttribsSchema, extAttribsSchemaManager)
  }

  def extractExtendedAttributes (assertion : Source, extendedAttribs : Destination, asJSON : Boolean, validate : Boolean, xsdEngine : String) : Unit = {
    val xdmDest = new XdmDestination

    val dest : Destination = {
      if (asJSON) {
        xdmDest
      } else if (validate) {
        new TeeDestination (extendedAttribs, xdmDest)
      } else {
        extendedAttribs
      }
    }

    val extractTrans = getXsltTransformer(extractExtExec)
    extractTrans.setSource(assertion)
    extractTrans.setDestination(dest)
    extractTrans.transform()

    if (validate) {
      validateExtAttributes (xdmDest.getXdmNode.asSource,  xsdEngine)
    }

    if (asJSON) {
      val evaluator = getXQueryEvaluator(ext2JSONExec)
      evaluator.setSource(xdmDest.getXdmNode.asSource)
      evaluator.setDestination(extendedAttribs)
      evaluator.run()
    }
  }

  def extractExtendedAttributes (assertion : Source, validate : Boolean, xsdEngine : String) : JsonNode = {
    val om = new ObjectMapper
    val bout = new ByteArrayOutputStream
    val dest = processor.newSerializer (bout)

    extractExtendedAttributes(assertion, dest, true, validate, xsdEngine)
    om.readTree (bout.toByteArray)
  }

  def addExtendedAttributes (authResp : Source, assertion : Source, newAuthResp : Destination,
                             isJSON : Boolean, validate : Boolean, xsdEngine : String) : Unit = {
    if (!isJSON) {
      addExtendedAttributes (authResp, assertion, newAuthResp, validate, xsdEngine)
    } else {
      val ow = (new ObjectMapper).writer(SerializationFeature.INDENT_OUTPUT)
      val jsonOut = addExtendedAttributes (authResp.asInstanceOf[StreamSource], assertion, validate, xsdEngine)
      newAuthResp.asInstanceOf[Serializer].getOutputDestination match {
        case f : File => ow.writeValue (f, jsonOut)
        case o : OutputStream => ow.writeValue (o, jsonOut)
        case w : Writer => ow.writeValue (w, jsonOut)
      }
    }
  }

  def addExtendedAttributes (authResp : Source, assertion : Source, newAuthResp : Destination,
                             validate : Boolean, xsdEngine : String) : Unit = {
    val extDest = new XdmDestination
    extractExtendedAttributes (assertion, extDest, false, validate, xsdEngine)

    val joinExtTrans = getXsltTransformer(joinExtExec, Map[QName, XdmValue](new QName("extAttributes")->extDest.getXdmNode))
    joinExtTrans.setSource(authResp)
    joinExtTrans.setDestination(newAuthResp)
    joinExtTrans.transform()
  }

  def addExtendedAttributes (authResp : Source, assertion : Document, validate : Boolean, xsdEngine : String) : Document = {
    var docBuilder : DocumentBuilder = null
    var outDoc : Document = null
    try {
      docBuilder = XMLParserPool.borrowParser
      outDoc = docBuilder.newDocument
    } finally {
      if (docBuilder != null) XMLParserPool.returnParser(docBuilder)
    }

    val dest = new DOMDestination(outDoc)
    addExtendedAttributes (authResp, new DOMSource(assertion), dest,
                           validate, xsdEngine)
    outDoc
  }

  def addExtendedAttributes (authResp : Document, assertion : Document, validate : Boolean, xsdEngine : String) : Document = {
    addExtendedAttributes (new DOMSource(authResp), assertion, validate, xsdEngine)
  }

  def addExtendedAttributes (authResp : JsonNode, assertion : Source, validate : Boolean, xsdEngine : String) : JsonNode = {
    val extAttribs = extractExtendedAttributes (assertion, validate, xsdEngine)
    val retResp : ObjectNode  = authResp.deepCopy[ObjectNode]
    if (extAttribs.get("RAX-AUTH:extendedAttributes").size != 0) {
      val accessObj = retResp.get("access").asInstanceOf[ObjectNode]
      accessObj.set("RAX-AUTH:extendedAttributes",extAttribs.get("RAX-AUTH:extendedAttributes"))
    }
    retResp
  }

  def addExtendedAttributes (authResp : StreamSource, assertion : Source, validate : Boolean, xsdEngine : String) : JsonNode = {
    addExtendedAttributes (parseJsonNode(authResp), assertion, validate, xsdEngine)
  }

  def addExtendedAttributes (authResp : JsonNode, assertion : Document, validate : Boolean, xsdEngine : String) : JsonNode = {
    addExtendedAttributes (authResp, new DOMSource(assertion), validate, xsdEngine)
  }
}
