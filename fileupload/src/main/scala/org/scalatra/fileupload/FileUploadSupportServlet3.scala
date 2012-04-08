package org.scalatra.fileupload

import scala.collection.JavaConversions._
import javax.servlet.annotation.MultipartConfig
import javax.servlet.http.{HttpServletRequest, Part}
import org.scalatra.servlet.{ServletRequest, ServletBase}
import java.util.{HashMap => JHashMap, Map => JMap}

@MultipartConfig
trait FileUploadSupportServlet3 extends ServletBase {
  import FileUploadSupportServlet3._

  override def handle(req: RequestT, res: ResponseT) {
    val req2 = try {
      if (isMultipartRequest(req)) {
        val bodyParams       = extractMultipartParams(req)
        val mergedFormParams = mergeFormParamsWithQueryString(req, bodyParams)

        wrapRequest(req, mergedFormParams)
      } else req
    } catch {
      case e: Exception => req
    }

    super.handle(req2, res)
  }

  private def isMultipartRequest(req: RequestT): Boolean = {
    val isPostOrPut = Set("POST", "PUT").contains(req.getMethod)

    isPostOrPut && (req.contentType match {
      case Some(contentType) => contentType.startsWith("multipart/")
      case _                 => false
    })
  }

  private def extractMultipartParams(req: RequestT): BodyParams = {
    req.get(BodyParamsKey).asInstanceOf[Option[BodyParams]] match {
      case Some(bodyParams) =>
        bodyParams

      case None => {
        req.getParts.foldRight(BodyParams(FileMultiParamsServlet3(), Map.empty)) { (part, params) =>
          val fname = fileName(part)

          if (fname.isDefined) {
            BodyParams(params.fileParams + ((
              part.getName, FileItemServlet3(fname.get, part) +: params.fileParams.getOrElse(part.getName, List[FileItemServlet3]())
              )), params.formParams)
          } else {
            BodyParams(params.fileParams, params.formParams + (
              (part.getName, partToString(part) ::
                params.formParams.getOrElse(part.getName, List[String]())
              )
            ))
          }
        }
      }
    }
  }

  private def partToString(part: Part): String = {
    import org.scalatra.util.io.readBytes

    val charset = getHeaderAttribute(part, "content-type", "charset", defaultCharacterEncoding)
    new String(readBytes(part.getInputStream), charset)
  }

  private def fileName(part: Part): Option[String] = {
    Option(getHeaderAttribute(part, "content-disposition", "filename", null))
  }

  private def mergeFormParamsWithQueryString(req: RequestT, bodyParams: BodyParams): Map[String, List[String]] = {
    var mergedParams = bodyParams.formParams
    req.getParameterMap.asInstanceOf[JMap[String, Array[String]]] foreach {
      case (name, values) =>
        val formValues = mergedParams.getOrElse(name, List.empty)
        mergedParams += name -> (values.toList ++ formValues)
    }

    mergedParams
  }

  private def wrapRequest(req: HttpServletRequest, formMap: Map[String, Seq[String]]) = {
    val wrapped = new ServletRequest(req) {
      override def getParameter(name: String) = formMap.get(name) map { _.head } getOrElse null
      override def getParameterNames = formMap.keysIterator
      override def getParameterValues(name: String) = formMap.get(name) map { _.toArray } getOrElse null
      override def getParameterMap = new JHashMap[String, Array[String]] ++ (formMap transform { (k, v) => v.toArray })
    }
    wrapped
  }

  private def getHeaderAttribute(part: Part, headerName: String, attributeName: String, defaultValue: String = "") = Option(part.getHeader(headerName)) match {
    case Some(value) => {
      value.split(";").find(_.trim().startsWith(attributeName)) match {
        case Some(attributeValue) => attributeValue.substring(attributeValue.indexOf('=') + 1).trim().replace("\"", "")
        case _                    => defaultValue
      }
    }

    case _ => defaultValue
  }

  protected def fileMultiParams: FileMultiParamsServlet3 = extractMultipartParams(request).fileParams

  protected val _fileParams = new collection.Map[String, FileItemServlet3] {
    def get(key: String) = fileMultiParams.get(key) flatMap { _.headOption }
    override def size = fileMultiParams.size
    override def iterator = (fileMultiParams map {
      case (k, v) => (k, v.head)
    }).iterator
    override def -(key: String) = Map() ++ this - key
    override def +[B1 >: FileItemServlet3](kv: (String, B1)) = Map() ++ this + kv
  }

  /** @return a Map, keyed on the names of multipart file upload parameters, of all multipart files submitted with the request */
  def fileParams = _fileParams
}

object FileUploadSupportServlet3 {
  private val BodyParamsKey = "org.scalatra.fileupload.bodyParams"
  case class BodyParams(fileParams: FileMultiParamsServlet3, formParams: Map[String, List[String]])
}