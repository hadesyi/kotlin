package bean

import org.springframework.beans.factory.annotation.Value

class KotlinRef {
    @Value("#{ nameJ }") private val valueA: BeanA? = null
    @Value("#{ nameK }") private val valueK: BeanA? = null
}
