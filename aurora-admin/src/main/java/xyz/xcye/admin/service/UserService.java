package xyz.xcye.admin.service;

import org.springframework.validation.BindException;
import xyz.xcye.admin.exception.UserException;
import xyz.xcye.admin.vo.UserVO;
import xyz.xcye.common.dos.UserAccountDO;
import xyz.xcye.common.dos.UserDO;
import xyz.xcye.common.dto.PaginationDTO;
import xyz.xcye.common.entity.result.ModifyResult;

import java.util.List;

/**
 * 操作用户的service层
 * @author qsyyke
 */

public interface UserService {
    /**
     * 插入用户数据
     * @param userDO 用户的信息
     * @param userAccountDO 用户账户信息
     * @return
     * @throws BindException 对象属性错误
     * @throws UserException 插入，更新异常
     */
    ModifyResult insertUser(UserDO userDO, UserAccountDO userAccountDO) throws UserException, InstantiationException, IllegalAccessException;

    /**
     * 更新用户信息
     * @param userDO 需要更新的用户数据
     * @return
     * @throws BindException
     */
    ModifyResult updateUser(UserDO userDO) throws UserException;

    /**
     * 修改用户的删除状态，同时会修改用户的账户信息删除状态
     * @param userDO
     * @return
     * @throws UserException
     */
    ModifyResult updateDeleteStatus(UserDO userDO) throws UserException, InstantiationException, IllegalAccessException;

    /**
     * 根据uid删除用户，同时会删除au_user_account中相关的记录
     * @param uid
     * @return
     * @throws UserException
     */
    ModifyResult deleteByUid(long uid) throws UserException, InstantiationException, IllegalAccessException;

    /**
     * 根据userDO中的信息查询所有满足的数据，没有模糊查询
     * @param userDO
     * @param paginationDTO
     * @return
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    List<UserVO> queryAll(UserDO userDO, PaginationDTO paginationDTO) throws InstantiationException, IllegalAccessException;

    /**
     * 根据uid查询用户数据
     * @param uid
     * @return
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    UserVO queryByUid(long uid) throws InstantiationException, IllegalAccessException;

    UserDO queryByUsername(String username);
}